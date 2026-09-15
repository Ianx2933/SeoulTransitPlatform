"""
EN: Solve day-type hourly boarding ratios from monthly aggregate API data.
KR: 월 누적 API 데이터에서 day-type별 시간대 승차 비율을 추정한다.

EN:
For each route-stop-hour, this script solves:

    A x = b

A = monthly day-type counts
x = average boarding passengers for each day type
b = monthly hourly boarding passengers

Routes are processed in batches so memory stays bounded as months are added.
Groups that observed the same set of months share the same A, so they are
solved together in one least-squares call. The result is identical to
solving each group separately.

KR:
각 노선-정류장-hour 단위로 다음 식을 푼다.

    A x = b

A = 월별 day-type 개수
x = day-type별 평균 승차량
b = 월별 해당 hour 승차량

월 수가 늘어나도 메모리가 일정하도록 노선 단위로 나눠 처리한다.
같은 월 집합을 가진 그룹은 A가 같으므로 한 번의 최소제곱 호출로 함께 푼다.
그룹별로 따로 푼 결과와 동일하다.
"""

from __future__ import annotations

import argparse

import numpy as np
import pandas as pd
from sqlalchemy import text

from common import DAY_TYPES, make_engine


KEY_COLUMNS = ["route_no", "stop_ars", "hour"]


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


def load_route_list(engine) -> list[str]:
    """
    EN: List routes so the monthly table can be read in bounded batches.
    KR: 월 테이블을 나눠 읽기 위해 노선 목록을 가져온다.
    """
    df = pd.read_sql("""
        SELECT DISTINCT route_no
        FROM hourly_bus_stop_passenger
        ORDER BY route_no
    """, engine)
    return df["route_no"].tolist()


def load_hourly_monthly(engine, routes: list[str] | None = None) -> pd.DataFrame:
    """
    EN: Load monthly hourly boarding counts by route-stop-hour.
    KR: 노선-정류장-hour별 월 누적 승차량을 읽는다.

    EN:
    Only 5-digit ARS codes are kept. Virtual stops arrive as '~' and blank codes
    normalize to '00000'; both would distort the decomposition model.

    KR:
    5자리 숫자 ARS만 사용한다. 가상 정류장은 '~', 빈 코드는 '00000'으로 들어오며
    둘 다 요일별 시간대 패턴 분해를 왜곡한다.
    """
    route_filter = "AND route_no = ANY(:routes)" if routes is not None else ""
    sql = text(f"""
        SELECT
            use_ym,
            route_no,
            stop_ars,
            hour,
            SUM(boarding_passengers) AS monthly_boarding
        FROM hourly_bus_stop_passenger
        WHERE stop_ars ~ '^[0-9][0-9][0-9][0-9][0-9]$'
          AND stop_ars <> '00000'
          {route_filter}
        GROUP BY use_ym, route_no, stop_ars, hour
    """)
    params = {"routes": list(routes)} if routes is not None else {}
    return pd.read_sql(sql, engine, params=params)


def nonnegative_lstsq(A: np.ndarray, b: np.ndarray) -> np.ndarray:
    """
    EN: Solve least squares and clip negative estimates to zero.
    KR: 최소제곱해를 구한 뒤 음수 추정값을 0으로 보정한다.

    EN:
    b may be a vector or a matrix with one column per group.

    KR:
    b는 벡터이거나, 그룹마다 한 열인 행렬일 수 있다.
    """
    x, *_ = np.linalg.lstsq(A, b, rcond=None)
    return np.clip(x, 0, None)


def solve_grouped(month_counts: pd.DataFrame, hourly: pd.DataFrame, min_months: int,
                  value_columns: list[str]):
    """
    EN: Solve A x = b for every route-stop-hour group without a Python loop per group.
    KR: 그룹마다 파이썬 루프를 돌지 않고 모든 노선-정류장-hour의 A x = b를 푼다.

    Returns (keys, solutions) where keys holds one row per solved group and
    solutions maps each value column to an array of shape (groups, day types).
    """
    months = month_counts["use_ym"].astype(str).str.strip()
    if months.duplicated().any():
        raise ValueError("month_day_count has duplicate use_ym values")
    month_position = pd.Series(np.arange(len(months)), index=months.to_numpy())
    A_all = month_counts[DAY_TYPES].astype(float).to_numpy()

    position = hourly["use_ym"].astype(str).str.strip().map(month_position)
    keep = position.notna().to_numpy()
    data = hourly.loc[keep].reset_index(drop=True)
    if data.empty:
        return None, None
    month_pos = position[keep].astype(int).to_numpy()

    gid = data.groupby(KEY_COLUMNS, sort=True).ngroup().to_numpy()
    n_groups, n_months = int(gid.max()) + 1, len(months)

    cell = gid.astype(np.int64) * n_months + month_pos
    if np.unique(cell).size != cell.size:
        raise ValueError("hourly data has more than one row per group and month")

    present = np.zeros((n_groups, n_months), dtype=bool)
    present[gid, month_pos] = True
    values = {}
    for column in value_columns:
        matrix = np.zeros((n_groups, n_months))
        matrix[gid, month_pos] = data[column].astype(float).to_numpy()
        values[column] = matrix

    _, first_row = np.unique(gid, return_index=True)
    keys = data.loc[first_row, KEY_COLUMNS].reset_index(drop=True)

    # EN: Skip sparse groups because underdetermined estimates are unstable.
    # KR: 월 수가 부족한 그룹은 추정이 불안정하므로 건너뛴다.
    valid = np.flatnonzero(present.sum(axis=1) >= min_months)
    solutions = {column: np.zeros((n_groups, len(DAY_TYPES))) for column in value_columns}

    if valid.size:
        patterns, inverse = np.unique(present[valid], axis=0, return_inverse=True)
        inverse = np.asarray(inverse).reshape(-1)
        for pattern_index, month_mask in enumerate(patterns):
            rows = valid[inverse == pattern_index]
            A = A_all[month_mask]
            for column in value_columns:
                b = values[column][rows][:, month_mask].T
                solutions[column][rows] = nonnegative_lstsq(A, b).T

    keys = keys.iloc[valid].reset_index(drop=True)
    solutions = {column: array[valid] for column, array in solutions.items()}
    return keys, solutions


def solve_patterns(month_counts: pd.DataFrame, hourly: pd.DataFrame, min_months: int) -> pd.DataFrame:
    """
    EN: Estimate day-type hourly averages and convert them into ratios.
    KR: day-type별 시간대 평균 승차량을 추정하고 ratio로 변환한다.
    """
    keys, solutions = solve_grouped(month_counts, hourly, min_months, ["monthly_boarding"])
    if keys is None or keys.empty:
        return pd.DataFrame()

    n_day_types = len(DAY_TYPES)
    avg_df = pd.DataFrame({
        "route_no": np.repeat(keys["route_no"].to_numpy(), n_day_types),
        "stop_ars": np.repeat(keys["stop_ars"].to_numpy(), n_day_types),
        "day_type": np.tile(np.array(DAY_TYPES, dtype=object), len(keys)),
        "hour": np.repeat(keys["hour"].astype(int).to_numpy(), n_day_types),
        "avg_boarding_passengers": solutions["monthly_boarding"].reshape(-1),
    })

    # EN: Normalize 24 hourly averages within each route-stop-day_type into ratios.
    # KR: 노선-정류장-day_type별 24시간 평균값을 합이 1인 ratio로 정규화한다.
    total_by_day = (
        avg_df.groupby(["route_no", "stop_ars", "day_type"])["avg_boarding_passengers"]
        .transform("sum")
    )

    avg_df["boarding_ratio"] = np.where(
        total_by_day > 0,
        avg_df["avg_boarding_passengers"] / total_by_day.where(total_by_day > 0, 1.0),
        0.0,
    )
    avg_df["method"] = "dow_least_squares_sun_holiday"

    return avg_df


UPSERT_SQL = text("""
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


def upsert_ratios(conn, ratio_df: pd.DataFrame, batch_size: int) -> None:
    """
    EN: Write ratio rows through an open connection in bounded batches.
    KR: 열린 connection으로 ratio row를 일정 크기 batch로 나눠 저장한다.
    """
    if ratio_df.empty:
        return
    records = ratio_df.to_dict(orient="records")
    for start in range(0, len(records), batch_size):
        conn.execute(UPSERT_SQL, records[start:start + batch_size])


def save_ratios(engine, ratio_df: pd.DataFrame, batch_size: int = 50000) -> None:
    """
    EN: Persist ratios in one transaction. Kept for callers of the previous version.
    KR: 한 트랜잭션으로 ratio를 저장한다. 이전 버전 호출부 호환용.
    """
    if ratio_df.empty:
        print("No ratios generated.")
        return
    with engine.begin() as conn:
        upsert_ratios(conn, ratio_df, batch_size)


def main():
    """
    EN: CLI entry point for least-squares ratio estimation.
    KR: least-squares 기반 ratio 추정 CLI 진입점.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--min-months", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=50000)
    parser.add_argument("--route-batch", type=int, default=50,
                        help="Routes read and solved per batch. Lower it if memory is tight.")
    args = parser.parse_args()

    engine = make_engine(args.db_url)
    month_counts = load_month_counts(engine)
    routes = load_route_list(engine)

    print(f"Loaded month count rows: {len(month_counts)}")
    print(f"Routes: {len(routes)} (batch {args.route_batch})")

    total_input = 0
    total_output = 0

    # EN: One transaction for all batches, so an interrupted run leaves the old ratios intact.
    # KR: 전체를 한 트랜잭션으로 묶어, 중간에 끊겨도 기존 ratio가 그대로 남게 한다.
    with engine.begin() as conn:
        for start in range(0, len(routes), args.route_batch):
            batch = routes[start:start + args.route_batch]
            hourly = load_hourly_monthly(engine, batch)
            ratio_df = solve_patterns(month_counts, hourly, args.min_months)
            upsert_ratios(conn, ratio_df, args.batch_size)

            total_input += len(hourly)
            total_output += len(ratio_df)
            done = min(start + args.route_batch, len(routes))
            print(f"routes {done}/{len(routes)}: monthly hourly rows {total_input:,}; "
                  f"ratio rows {total_output:,}")

    print(f"Done. Generated ratio rows: {total_output:,}")


if __name__ == "__main__":
    main()
