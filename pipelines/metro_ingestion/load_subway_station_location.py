"""
Metro Ingestion - Subway Station Location Loader

Purpose: Load Seoul subway station master CSV into PostgreSQL.

Input: data/reference/서울시 역사마스터 정보.csv

Output table: subway_station_location
"""

from dataclasses import dataclass
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine


# =========================
# Config Layer
# =========================

@dataclass(frozen=True)
class MetroIngestionConfig:
    """Runtime configuration for metro ingestion."""

    csv_path: Path
    db_url: str
    table_name: str = "subway_station_location"
    csv_encoding: str = "cp949"


# =========================
# Domain / Transformation Layer
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
    """Validate required source columns."""

    missing_columns = [col for col in REQUIRED_SOURCE_COLUMNS if col not in df.columns]
    if missing_columns:
        raise ValueError(f"Missing required columns: {missing_columns}")


def transform_station_location(df: pd.DataFrame) -> pd.DataFrame:
    """
    Normalize source CSV columns into DB table schema.
    """

    validate_source_columns(df)

    transformed = df.rename(columns=COLUMN_MAPPING)[TARGET_COLUMNS].copy()

    # Keep station_id as string to avoid type mismatch with service-layer DTOs.
    transformed["station_id"] = transformed["station_id"].astype(str).str.strip()

    # Normalize text fields for safer joins with demand tables.
    transformed["station_name"] = transformed["station_name"].astype(str).str.strip()
    transformed["line_name"] = transformed["line_name"].astype(str).str.strip()

    # Enforce numeric coordinate types.
    transformed["lat"] = pd.to_numeric(transformed["lat"], errors="coerce")
    transformed["lng"] = pd.to_numeric(transformed["lng"], errors="coerce")

    # Drop rows that cannot be used for map rendering.
    transformed = transformed.dropna(
        subset=["station_id", "station_name", "line_name", "lat", "lng"]
    )

    # Remove duplicated station_id rows before DB upsert.
    transformed = transformed.drop_duplicates(subset=["station_id"], keep="last")

    return transformed


# =========================
# Infrastructure Layer
# =========================

def create_db_engine(db_url: str) -> Engine:
    """Create SQLAlchemy engine."""

    return create_engine(db_url)


def read_station_csv(csv_path: Path, encoding: str) -> pd.DataFrame:
    """Read subway station master CSV."""

    if not csv_path.exists():
        raise FileNotFoundError(f"CSV file not found: {csv_path}")

    return pd.read_csv(csv_path, encoding=encoding)


def ensure_station_location_table(engine: Engine, table_name: str) -> None:
    """
    Create target table if it does not exist.
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
    """

    if df.empty:
        raise ValueError("No valid station location rows to load.")

    temp_table_name = f"tmp_{table_name}"

    with engine.begin() as connection:
        # Load into temporary staging table first.
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
    """Count loaded rows in target table."""

    with engine.begin() as connection:
        result = connection.execute(text(f"SELECT COUNT(*) FROM {table_name};"))
        return int(result.scalar_one())


# =========================
# Application Layer
# =========================

def load_subway_station_location(config: MetroIngestionConfig) -> None:
    """
    Execute subway station location ingestion use case.
    """

    engine = create_db_engine(config.db_url)

    raw_df = read_station_csv(config.csv_path, config.csv_encoding)
    station_location_df = transform_station_location(raw_df)

    ensure_station_location_table(engine, config.table_name)
    upsert_station_location(engine, config.table_name, station_location_df)

    loaded_count = count_loaded_rows(engine, config.table_name)
    print(f"Loaded station location rows: {loaded_count}")


# =========================
# Entry Point
# =========================

def main() -> None:
    """CLI entry point."""

    project_root = Path(__file__).resolve().parents[2]

    config = MetroIngestionConfig(
        csv_path=project_root / "data" / "reference" / "서울시 역사마스터 정보.csv",
        db_url="postgresql+psycopg2://postgres:330218@localhost:5432/Seoul_Transit",
    )

    load_subway_station_location(config)


if __name__ == "__main__":
    main()
