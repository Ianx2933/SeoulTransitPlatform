"""
Train XGBoost model with an improved day-of-week feature schema.

Data source:
- analysis_table_final

Target:
- log1p(passenger_count)

Main feature additions:
- weekday/weekend/holiday grouping
- rolling 7-day and 14-day stop demand
- route-level daily trend
- stop-level average demand
- upper-tail clipping for outlier stabilization
"""

import argparse
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sqlalchemy import create_engine
from sklearn.metrics import mean_absolute_error, r2_score
from sklearn.model_selection import train_test_split
from xgboost import XGBRegressor


FEATURE_COLUMNS = [
    "dayOfWeek",
    "isWeekend",
    "dayGroup",
    "isHoliday",
    "month",
    "routeCode",
    "stopCode",
    "prevWeek",
    "prevMonth",
    "rolling7",
    "rolling14",
    "routeDailyTotal",
    "routeRolling7",
    "stopMean",
]

TARGET_TRANSFORM = "log1p"
OUTLIER_CLIP_QUANTILE = 0.995


def load_daily_data(db_url: str) -> pd.DataFrame:
    """
    Load route-stop daily boarding counts.

    analysis_table_final is OD-level data, so this query aggregates it into
    route-stop-date training rows for congestion prediction.
    """
    sql = """
        SELECT
            기준일자,
            노선명 AS 노선번호,
            승차_정류장ars AS 버스정류장ars번호,
            SUM(CAST(승객수 AS INT)) AS passenger_count
        FROM analysis_table_final
        WHERE 승차_정류장ars IS NOT NULL
          AND 승차_정류장ars != '00000'
        GROUP BY 기준일자, 노선명, 승차_정류장ars
    """
    return pd.read_sql(sql, create_engine(db_url))


def load_holidays(db_url: str) -> set:
    try:
        df = pd.read_sql("SELECT 날짜 FROM holiday_config", create_engine(db_url))
    except Exception:
        return set()

    if df.empty:
        return set()

    return set(pd.to_datetime(df["날짜"]).dt.normalize())


def normalize_raw_frame(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()

    df["기준일자"] = pd.to_datetime(df["기준일자"], format="%Y%m%d", errors="coerce")
    df["노선번호"] = df["노선번호"].astype(str).str.strip()
    df["버스정류장ars번호"] = (
        df["버스정류장ars번호"]
        .astype(str)
        .str.strip()
        .str.replace(r"\.0$", "", regex=True)
        .str.zfill(5)
    )
    df["passenger_count"] = pd.to_numeric(df["passenger_count"], errors="coerce")

    df = df.dropna(subset=["기준일자", "노선번호", "버스정류장ars번호", "passenger_count"])
    df = df[df["passenger_count"] >= 0]

    return df


def clip_outliers(df: pd.DataFrame, quantile: float = OUTLIER_CLIP_QUANTILE) -> tuple[pd.DataFrame, dict]:
    """
    Clip upper-tail passenger counts instead of deleting rows.

    This preserves time-series continuity for prevWeek, prevMonth, and rolling features.
    """
    df = df.copy()

    clip_upper = float(df["passenger_count"].quantile(quantile))
    original_max = float(df["passenger_count"].max())

    df["passenger_count"] = df["passenger_count"].clip(upper=clip_upper)

    stats = {
        "method": "upper_clip",
        "quantile": quantile,
        "clip_upper": clip_upper,
        "original_max": original_max,
    }
    return df, stats


def add_calendar_features(df: pd.DataFrame, holiday_dates: set) -> pd.DataFrame:
    df = df.copy()

    df["dayOfWeek"] = df["기준일자"].dt.dayofweek
    df["isWeekend"] = (df["dayOfWeek"] >= 5).astype(int)
    df["isHoliday"] = df["기준일자"].dt.normalize().isin(holiday_dates).astype(int)
    df["month"] = df["기준일자"].dt.month

    # 0 = weekday, 1 = weekend, 2 = holiday.
    # Holiday overrides weekday/weekend because holiday demand often behaves differently.
    df["dayGroup"] = np.where(df["isWeekend"] == 1, 1, 0)
    df.loc[df["isHoliday"] == 1, "dayGroup"] = 2

    return df


def add_route_stop_codes(df: pd.DataFrame) -> tuple[pd.DataFrame, dict, dict]:
    df = df.copy()

    route_code_map = {v: i for i, v in enumerate(sorted(df["노선번호"].unique()))}
    stop_code_map = {v: i for i, v in enumerate(sorted(df["버스정류장ars번호"].unique()))}

    df["routeCode"] = df["노선번호"].map(route_code_map).astype(int)
    df["stopCode"] = df["버스정류장ars번호"].map(stop_code_map).astype(int)

    return df, route_code_map, stop_code_map


def add_stop_history_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Add route-stop historical demand features.

    shift(1) is used before rolling means to avoid using the current target row
    as an input feature.
    """
    df = df.copy()
    df = df.sort_values(["노선번호", "버스정류장ars번호", "기준일자"])

    group = df.groupby(["노선번호", "버스정류장ars번호"])["passenger_count"]

    df["prevWeek"] = group.shift(7)
    df["prevMonth"] = group.shift(30)

    shifted = group.shift(1)
    df["rolling7"] = (
        shifted.groupby([df["노선번호"], df["버스정류장ars번호"]])
        .rolling(window=7, min_periods=1)
        .mean()
        .reset_index(level=[0, 1], drop=True)
    )
    df["rolling14"] = (
        shifted.groupby([df["노선번호"], df["버스정류장ars번호"]])
        .rolling(window=14, min_periods=1)
        .mean()
        .reset_index(level=[0, 1], drop=True)
    )

    # Stop-level baseline demand. This gives the model the typical scale of each stop.
    stop_mean = df.groupby("버스정류장ars번호")["passenger_count"].mean()
    df["stopMean"] = df["버스정류장ars번호"].map(stop_mean)

    return df


def add_route_trend_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Add route-level trend features.

    routeDailyTotal is the total daily boarding count for a route.
    routeRolling7 uses previous route-level daily totals, so it avoids current-row leakage.
    """
    df = df.copy()

    route_daily = (
        df.groupby(["노선번호", "기준일자"], as_index=False)["passenger_count"]
        .sum()
        .rename(columns={"passenger_count": "routeDailyTotal"})
        .sort_values(["노선번호", "기준일자"])
    )

    route_daily["routeRolling7"] = (
        route_daily.groupby("노선번호")["routeDailyTotal"]
        .shift(1)
        .groupby(route_daily["노선번호"])
        .rolling(window=7, min_periods=1)
        .mean()
        .reset_index(level=0, drop=True)
    )

    df = df.merge(route_daily, on=["노선번호", "기준일자"], how="left")

    return df


def finalize_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()

    for col in FEATURE_COLUMNS:
        df[col] = pd.to_numeric(df[col], errors="coerce")

    # History features are unavailable for early rows. Use 0 as an explicit "no history yet" signal.
    history_cols = ["prevWeek", "prevMonth", "rolling7", "rolling14", "routeRolling7"]
    df[history_cols] = df[history_cols].fillna(0)

    # Baseline/trend features should rarely be missing, but fill defensively.
    df["routeDailyTotal"] = df["routeDailyTotal"].fillna(0)
    df["stopMean"] = df["stopMean"].fillna(df["passenger_count"].mean())

    return df


def build_training_frame(df: pd.DataFrame, holiday_dates: set):
    df = normalize_raw_frame(df)
    df, outlier_stats = clip_outliers(df)
    df = add_calendar_features(df, holiday_dates)
    df, route_code_map, stop_code_map = add_route_stop_codes(df)
    df = add_stop_history_features(df)
    df = add_route_trend_features(df)
    df = finalize_features(df)

    feature_defaults = {
        col: float(df[col].median()) for col in FEATURE_COLUMNS
    }
    feature_defaults.update({
        "unknownRouteCode": -1,
        "unknownStopCode": -1,
        "targetTransform": TARGET_TRANSFORM,
    })

    return df, route_code_map, stop_code_map, feature_defaults, outlier_stats


def train_model(df: pd.DataFrame):
    X = df[FEATURE_COLUMNS]
    y_raw = df["passenger_count"]
    y = np.log1p(y_raw)

    X_train, X_test, y_train, y_test = train_test_split(
        X,
        y,
        test_size=0.2,
        random_state=42,
        shuffle=True,
    )

    model = XGBRegressor(
        n_estimators=500,
        max_depth=7,
        learning_rate=0.04,
        subsample=0.85,
        colsample_bytree=0.85,
        objective="reg:squarederror",
        random_state=42,
        n_jobs=-1,
    )

    model.fit(X_train, y_train)

    pred_log = model.predict(X_test)
    pred = np.expm1(pred_log)
    y_true = np.expm1(y_test)

    pred = np.maximum(pred, 0)

    metrics = {
        "mae": float(mean_absolute_error(y_true, pred)),
        "r2": float(r2_score(y_true, pred)),
        "log_mae": float(mean_absolute_error(y_test, pred_log)),
        "log_r2": float(r2_score(y_test, pred_log)),
        "train_rows": int(len(X_train)),
        "test_rows": int(len(X_test)),
        "target_transform": TARGET_TRANSFORM,
        "feature_columns": FEATURE_COLUMNS,
    }

    return model, metrics


def save(
    output: Path,
    model,
    route_code_map,
    stop_code_map,
    feature_defaults,
    metrics,
    outlier_stats,
):
    output.mkdir(parents=True, exist_ok=True)

    joblib.dump(model, output / "congestion_model.pkl")
    joblib.dump(route_code_map, output / "route_code_map.pkl")
    joblib.dump(stop_code_map, output / "stop_code_map.pkl")

    (output / "feature_columns.json").write_text(
        json.dumps(FEATURE_COLUMNS, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output / "feature_defaults.json").write_text(
        json.dumps(feature_defaults, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    metrics = dict(metrics)
    metrics["outlier"] = outlier_stats

    (output / "training_metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    raw = load_daily_data(args.db_url)
    holidays = load_holidays(args.db_url)

    frame, route_map, stop_map, feature_defaults, outlier_stats = build_training_frame(raw, holidays)
    model, metrics = train_model(frame)

    save(
        Path(args.output),
        model,
        route_map,
        stop_map,
        feature_defaults,
        metrics,
        outlier_stats,
    )

    print(json.dumps(metrics | {"outlier": outlier_stats}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
