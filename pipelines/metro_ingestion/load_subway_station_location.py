"""
Metro Ingestion - Subway Station Location Loader
(지하철 데이터 적재 - 역사 위치 로더)

Purpose: Load Seoul subway station master CSV into PostgreSQL.
(목적: 서울 지하철 역사 마스터 CSV를 PostgreSQL에 적재합니다.)

Input: data/reference/서울시 역사마스터 정보.csv
(입력: 역사 마스터 CSV 파일)

Output table: subway_station_location
(출력 테이블: 지하철 역사 위치 마스터)
"""

import os
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine


# =========================
# Config Layer (설정 계층)
# =========================

@dataclass(frozen=True)
class MetroIngestionConfig:
    """Runtime configuration for metro ingestion. (지하철 적재 실행 설정)"""

    csv_path: Path
    db_url: str
    table_name: str = "subway_station_location"
    csv_encoding: str = "cp949"


# =========================
# Domain / Transformation Layer (도메인/변환 계층)
# =========================

REQUIRED_SOURCE_COLUMNS = ["역사_ID", "역사명", "호선", "위도", "경도"]

COLUMN_MAPPING = {
    "역사_ID": "station_id",
    "역사명": "station_name",
    "호선": "line_name",
    "위도": "lat",
    "경도": "lng",
}

TARGET_COLUMNS = ["station_id", "station_name", "line_name", "lat", "lng"]


def validate_source_columns(df: pd.DataFrame) -> None:
    """Validate required source columns. (필수 원본 컬럼 검증)"""

    missing_columns = [col for col in REQUIRED_SOURCE_COLUMNS if col not in df.columns]
    if missing_columns:
        raise ValueError(f"Missing required columns: {missing_columns}")


def transform_station_location(df: pd.DataFrame) -> pd.DataFrame:
    """
    Normalize source CSV columns into DB table schema.
    (원본 CSV 컬럼을 DB 테이블 스키마에 맞게 정규화합니다.)
    """

    validate_source_columns(df)

    transformed = df.rename(columns=COLUMN_MAPPING)[TARGET_COLUMNS].copy()

    # Keep station_id as string to avoid type mismatch with service-layer DTOs. (서비스 DTO와 타입 불일치를 피하기 위해 문자열 유지)
    transformed["station_id"] = transformed["station_id"].astype(str).str.strip()

    # Normalize text fields for safer joins with demand tables. (수요 테이블과 안전하게 조인하기 위해 문자열 정규화)
    transformed["station_name"] = transformed["station_name"].astype(str).str.strip()
    transformed["line_name"] = transformed["line_name"].astype(str).str.strip()

    # Enforce numeric coordinate types. (좌표는 숫자형으로 강제 변환)
    transformed["lat"] = pd.to_numeric(transformed["lat"], errors="coerce")
    transformed["lng"] = pd.to_numeric(transformed["lng"], errors="coerce")

    # Drop rows that cannot be used for map rendering. (지도 렌더링에 쓸 수 없는 행 제거)
    transformed = transformed.dropna(
        subset=["station_id", "station_name", "line_name", "lat", "lng"]
    )

    # Remove duplicated station_id rows before DB upsert. (DB upsert 전에 중복 station_id 제거)
    transformed = transformed.drop_duplicates(subset=["station_id"], keep="last")

    return transformed


# =========================
# Infrastructure Layer (인프라 계층)
# =========================

def create_db_engine(db_url: str) -> Engine:
    """Create SQLAlchemy engine. (SQLAlchemy 엔진 생성)"""

    return create_engine(db_url)


def read_station_csv(csv_path: Path, encoding: str) -> pd.DataFrame:
    """Read subway station master CSV. (지하철 역사 마스터 CSV 읽기)"""

    if not csv_path.exists():
        raise FileNotFoundError(f"CSV file not found: {csv_path}")

    return pd.read_csv(csv_path, encoding=encoding)


def ensure_station_location_table(engine: Engine, table_name: str) -> None:
    """
    Create target table if it does not exist.
    (대상 테이블이 없으면 생성합니다.)
    """

    create_table_sql = f"""
    CREATE TABLE IF NOT EXISTS {table_name} (
        station_id VARCHAR(50),
        station_name VARCHAR(100),
        line_name VARCHAR(100),
        lat DOUBLE PRECISION,
        lng DOUBLE PRECISION,
        PRIMARY KEY (station_id)
    );
    """

    with engine.begin() as connection:
        connection.execute(text(create_table_sql))


def upsert_station_location(engine: Engine, table_name: str, df: pd.DataFrame) -> None:
    """
    Upsert station location records into PostgreSQL.
    (지하철 역사 위치 레코드를 PostgreSQL에 upsert 합니다.)
    """

    if df.empty:
        raise ValueError("No valid station location rows to load.")

    temp_table_name = f"tmp_{table_name}"

    with engine.begin() as connection:
        # Load into temporary staging table first. (먼저 임시 staging 테이블에 적재)
        df.to_sql(temp_table_name, connection, if_exists="replace", index=False)

        upsert_sql = f"""
        INSERT INTO {table_name} (station_id, station_name, line_name, lat, lng)
        SELECT station_id, station_name, line_name, lat, lng
        FROM {temp_table_name}
        ON CONFLICT (station_id)
        DO UPDATE SET
            station_name = EXCLUDED.station_name,
            line_name = EXCLUDED.line_name,
            lat = EXCLUDED.lat,
            lng = EXCLUDED.lng;
        """

        connection.execute(text(upsert_sql))
        connection.execute(text(f"DROP TABLE IF EXISTS {temp_table_name};"))


def count_loaded_rows(engine: Engine, table_name: str) -> int:
    """Count loaded rows in target table. (대상 테이블의 적재 행 수 확인)"""

    with engine.begin() as connection:
        result = connection.execute(text(f"SELECT COUNT(*) FROM {table_name};"))
        return int(result.scalar_one())


# =========================
# Application Layer (애플리케이션 계층)
# =========================

def load_subway_station_location(config: MetroIngestionConfig) -> None:
    """
    Execute subway station location ingestion use case.
    (지하철 역사 위치 적재 유스케이스 실행)
    """

    engine = create_db_engine(config.db_url)

    raw_df = read_station_csv(config.csv_path, config.csv_encoding)
    station_location_df = transform_station_location(raw_df)

    ensure_station_location_table(engine, config.table_name)
    upsert_station_location(engine, config.table_name, station_location_df)

    loaded_count = count_loaded_rows(engine, config.table_name)
    print(f"Loaded station location rows: {loaded_count}")


# =========================
# Entry Point (진입점)
# =========================

DEFAULT_CSV_RELATIVE_PATH = Path("data") / "reference" / "서울시 역사마스터 정보.csv"


def main() -> None:
    """CLI entry point. (CLI 진입점)

    Configuration comes from environment variables so that no credential is
    stored in the repository.
    (인증정보가 저장소에 남지 않도록 환경변수에서 설정을 읽습니다.)

    Required:
        PIPELINE_DB_URL   SQLAlchemy URL, for example
                          postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit

    Optional:
        SUBWAY_STATION_CSV  Override the input CSV path.
    """

    project_root = Path(__file__).resolve().parents[2]

    db_url = os.environ.get("PIPELINE_DB_URL")
    if not db_url:
        raise SystemExit(
            "PIPELINE_DB_URL is not set.\n"
            "Example:\n"
            "  export PIPELINE_DB_URL="
            "'postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'"
        )

    csv_override = os.environ.get("SUBWAY_STATION_CSV")
    csv_path = Path(csv_override) if csv_override else project_root / DEFAULT_CSV_RELATIVE_PATH

    config = MetroIngestionConfig(
        csv_path=csv_path,
        db_url=db_url,
    )

    load_subway_station_location(config)


if __name__ == "__main__":
    main()
