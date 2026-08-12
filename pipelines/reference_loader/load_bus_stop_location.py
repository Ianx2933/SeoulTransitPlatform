"""
Bus Stop Location Loader
(버스 정류장 위치 로더)

Purpose:
- Load Seoul bus stop master CSV into PostgreSQL bus_stop_location table. (서울 버스 정류장 마스터 CSV를 PostgreSQL bus_stop_location 테이블에 적재)
- Normalize ARS code to 5 digits. (ARS 코드를 5자리로 정규화)
- Keep coordinates ready for congestion map APIs. (혼잡도/지도 API에서 바로 쓸 수 있도록 좌표 유지)

Usage:
    python load_bus_stop_location.py \
        --csv "../../data/reference/seoul_bus_stop_master.csv" \
        --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" \
        --replace
"""

import argparse
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text


TARGET_COLUMNS = ["노드id", "정류장번호", "정류장명", "경도", "위도"]


# Read source CSV with fallback encoding. (원본 CSV를 읽고 인코딩 실패 시 대체 인코딩 사용)
def read_source_csv(csv_path: Path) -> pd.DataFrame:
    try:
        return pd.read_csv(csv_path, encoding="cp949")
    except UnicodeDecodeError:
        return pd.read_csv(csv_path, encoding="utf-8-sig")


# Transform raw columns into the canonical DB shape. (원본 컬럼을 DB 기준 형태로 변환)
def transform(raw: pd.DataFrame) -> pd.DataFrame:
    required = ["정류장_ID", "정류장_번호", "정류장_명칭", "경도", "위도"]
    missing = [column for column in required if column not in raw.columns]

    if missing:
        raise ValueError(f"Missing required columns: {missing}")

    result = pd.DataFrame({
        "노드id": raw["정류장_ID"].astype(str).str.strip(),
        "정류장번호": raw["정류장_번호"].astype("Int64").astype(str).str.zfill(5),
        "정류장명": raw["정류장_명칭"].astype(str).str.strip(),
        "경도": pd.to_numeric(raw["경도"], errors="coerce"),
        "위도": pd.to_numeric(raw["위도"], errors="coerce"),
    })

    result = result.dropna(subset=TARGET_COLUMNS)
    result = result.drop_duplicates(subset=["노드id"])
    return result


# Ensure target table and lookup index exist. (대상 테이블과 조회 인덱스 보장)
def ensure_table(engine) -> None:
    create_sql = """
    CREATE TABLE IF NOT EXISTS bus_stop_location (
        노드id VARCHAR(30) PRIMARY KEY,
        정류장번호 VARCHAR(10) NOT NULL,
        정류장명 TEXT NOT NULL,
        경도 DOUBLE PRECISION,
        위도 DOUBLE PRECISION
    );
    """

    index_sql = """
    CREATE INDEX IF NOT EXISTS idx_bus_stop_location_ars_name
    ON bus_stop_location (정류장번호, 정류장명);
    """

    with engine.begin() as conn:
        conn.execute(text(create_sql))
        conn.execute(text(index_sql))


# Load transformed rows into PostgreSQL. (변환된 행을 PostgreSQL에 적재)
def load_to_postgres(df: pd.DataFrame, db_url: str, replace: bool) -> None:
    engine = create_engine(db_url)
    ensure_table(engine)

    with engine.begin() as conn:
        if replace:
            conn.execute(text("TRUNCATE TABLE bus_stop_location;"))

    df.to_sql(
        "bus_stop_location",
        engine,
        if_exists="append",
        index=False,
        method="multi",
        chunksize=1000,
    )


# Parse CLI arguments and run the loader. (CLI 인자를 읽고 로더 실행)
def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True, help="Path to Seoul bus stop master CSV (서울 버스 정류장 마스터 CSV 경로)")
    parser.add_argument("--db-url", required=True, help="SQLAlchemy PostgreSQL URL (SQLAlchemy용 PostgreSQL 접속 URL)")
    parser.add_argument("--replace", action="store_true", help="Truncate existing table before insert (삽입 전 기존 테이블 비우기)")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    raw = read_source_csv(csv_path)
    transformed = transform(raw)

    load_to_postgres(transformed, args.db_url, args.replace)

    print(f"Loaded rows: {len(transformed):,}")
    print("bus_stop_location import completed.")


if __name__ == "__main__":
    main()
