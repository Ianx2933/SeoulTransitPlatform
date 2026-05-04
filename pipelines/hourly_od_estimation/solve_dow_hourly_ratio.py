"""
EN: Solve day-type hourly boarding ratios from monthly aggregate API data.
KR: 월 누적 API 데이터에서 day-type별 시간대 승차 비율을 추정한다.

EN:
For each route-stop-hour, this script solves:

    A x = b

A = monthly day-type counts
x = average boarding passengers for each day type
b = monthly hourly boarding passengers

KR:
각 노선-정류장-hour 단위로 다음 식을 푼다.

    A x = b

A = 월별 day-type 개수
x = day-type별 평균 승차량
b = 월별 해당 hour 승차량
"""

from __future__ import annotations

import argparse

import numpy as np
import pandas as pd
from sqlalchemy import text

from common import DAY_TYPES, make_engine


def load_month_counts(engine) -> pd.DataFrame:
    """
    EN: Load the A-matrix source table.
    KR: A 행렬의 원천인 month_day_count 테이블을 읽는다.
    """
    return pd.read_sql("""
        SELECT use_ym, mon, tue, wed, thu, fri, sat, sun_holiday
        FROM month_day_count
        ORDER BY use_ym
    """, engine)


def load_hourly_monthly(engine) -> pd.DataFrame:
    """
    EN: Load monthly hourly boarding counts by route-stop-hour.
    KR: 노선-정류장-hour별 월 누적 승차량을 읽는다.

    EN:
    stop_ars = '00000' is excluded because it represents an invalid or virtual stop
    and can distort the decomposition model.

    KR:
    stop_ars = '00000'은 유효하지 않거나 가상 정류장을 의미하므로
    요일별 시간대 패턴 분해 모델에서 제외한다.
    """
    return pd.read_sql("""
        SELECT
            use_ym,
            route_no,
            stop_ars,
            hour,
            SUM(boarding_passengers) AS monthly_boarding
        FROM hourly_bus_stop_passenger
        WHERE stop_ars != '00000'
        GROUP BY use_ym, route_no, stop_ars, hour
    """, engine)


def nonnegative_lstsq(A: np.ndarray, b: np.ndarray) -> np.ndarray:
    """
    EN: Solve least squares and clip negative estimates to zero.
    KR: 최소제곱해를 구한 뒤 음수 추정값을 0으로 보정한다.

    EN:
    Negative passenger estimates are mathematically possible in unconstrained least squares,
    but they are invalid in the transit domain.

    KR:
    unconstrained least squares에서는 음수 추정값이 나올 수 있지만,
    승객 수 도메인에서는 음수가 불가능하므로 0으로 보정한다.
    """
    x, *_ = np.linalg.lstsq(A, b, rcond=None)
    return np.clip(x, 0, None)


def solve_patterns(month_counts: pd.DataFrame, hourly: pd.DataFrame, min_months: int) -> pd.DataFrame:
    """
    EN: Estimate day-type hourly averages and convert them into ratios.
    KR: day-type별 시간대 평균 승차량을 추정하고 ratio로 변환한다.
    """
    merged = hourly.merge(month_counts, on="use_ym", how="inner")
    if merged.empty:
        return pd.DataFrame()

    results = []

    for (route_no, stop_ars, hour), group in merged.groupby(["route_no", "stop_ars", "hour"]):
        # EN: Skip sparse groups because underdetermined estimates are unstable.
        # KR: 월 수가 부족한 그룹은 추정이 불안정하므로 건너뛴다.
        if group["use_ym"].nunique() < min_months:
            continue

        A = group[DAY_TYPES].astype(float).to_numpy()
        b = group["monthly_boarding"].astype(float).to_numpy()

        x = nonnegative_lstsq(A, b)

        for day_type, avg_value in zip(DAY_TYPES, x):
            results.append({
                "route_no": route_no,
                "stop_ars": stop_ars,
                "day_type": day_type,
                "hour": int(hour),
                "avg_boarding_passengers": float(avg_value),
            })

    if not results:
        return pd.DataFrame()

    avg_df = pd.DataFrame(results)

    # EN: Normalize 24 hourly averages within each route-stop-day_type into ratios.
    # KR: 노선-정류장-day_type별 24시간 평균값을 합이 1인 ratio로 정규화한다.
    total_by_day = (
        avg_df.groupby(["route_no", "stop_ars", "day_type"])["avg_boarding_passengers"]
        .transform("sum")
    )

    avg_df["boarding_ratio"] = np.where(
        total_by_day > 0,
        avg_df["avg_boarding_passengers"] / total_by_day,
        0.0,
    )
    avg_df["method"] = "dow_least_squares_sun_holiday"

    return avg_df


def save_ratios(engine, ratio_df: pd.DataFrame, batch_size: int = 50000) -> None:
    """
    EN: Persist day-type hourly ratios into dow_hourly_ratio using batch upsert.
    KR: day-type별 시간대 ratio를 batch upsert 방식으로 dow_hourly_ratio에 저장한다.

    EN:
    The generated ratio table can contain millions of rows. Inserting all rows in one
    executemany call can stall or fail, so this function writes data in bounded batches.

    KR:
    생성되는 ratio row 수가 수백만 건이 될 수 있다. 전체를 한 번에 insert하면 멈추거나
    실패할 수 있으므로 일정 크기의 batch로 나눠 저장한다.
    """
    if ratio_df.empty:
        print("No ratios generated.")
        return

    sql = text("""
        INSERT INTO dow_hourly_ratio (
            route_no, stop_ars, day_type, hour,
            avg_boarding_passengers, boarding_ratio, method
        )
        VALUES (
            :route_no, :stop_ars, :day_type, :hour,
            :avg_boarding_passengers, :boarding_ratio, :method
        )
        ON CONFLICT (route_no, stop_ars, day_type, hour)
        DO UPDATE SET
            avg_boarding_passengers = EXCLUDED.avg_boarding_passengers,
            boarding_ratio = EXCLUDED.boarding_ratio,
            method = EXCLUDED.method
    """)

    records = ratio_df.to_dict(orient="records")
    total = len(records)

    with engine.begin() as conn:
        for start in range(0, total, batch_size):
            batch = records[start:start + batch_size]
            conn.execute(sql, batch)
            print(f"{min(start + batch_size, total)}/{total} saved")


def main():
    """
    EN: CLI entry point for least-squares ratio estimation.
    KR: least-squares 기반 ratio 추정 CLI 진입점.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--min-months", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=50000)
    args = parser.parse_args()

    engine = make_engine(args.db_url)
    month_counts = load_month_counts(engine)
    hourly = load_hourly_monthly(engine)

    print(f"Loaded month count rows: {len(month_counts)}")
    print(f"Loaded monthly hourly rows: {len(hourly)}")

    ratio_df = solve_patterns(month_counts, hourly, args.min_months)
    print(f"Generated ratio rows before save: {len(ratio_df)}")

    save_ratios(engine, ratio_df, batch_size=args.batch_size)

    print(f"Generated ratio rows: {len(ratio_df)}")


if __name__ == "__main__":
    main()
