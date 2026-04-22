"""
OD Curated Data Inspection Script
(OD curated 데이터 점검 스크립트)

Purpose
-------
Inspect the curated OD output and summarize:
- null route IDs
- null boarding/alighting ARS
- null boarding/alighting standard codes
- value distributions for problematic rows
- export CSV files for manual review

Usage example
-------------
python pipelines/od_correction/src/inspect_curated_output.py ^
  --input data/curated/od_curated_20251111.csv ^
  --output-dir data/inspection/20251111 ^
  --delimiter "|" ^
  --encoding "utf-8-sig" ^
  --debug-columns
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

import pandas as pd


@dataclass
class InspectionConfig:
    """
    Runtime configuration for inspection
    (점검 실행 설정)
    """
    input_path: Path
    output_dir: Path
    delimiter: str = "|"
    encoding: str = "utf-8-sig"
    debug_columns: bool = False


class CuratedOutputInspector:
    """
    Inspect curated OD output
    (curated OD 결과 점검)
    """

    REQUIRED_COLUMNS = [
        "기준일자",
        "노선명",
        "전환_노선ID",
        "승차_정류장순번",
        "승차_정류장ARS",
        "승차_정류장표준코드",
        "승차_정류장명",
        "하차_정류장순번",
        "하차_정류장ARS",
        "하차_정류장표준코드",
        "하차_정류장명",
        "승객수",
    ]

    def __init__(self, config: InspectionConfig) -> None:
        self.config = config
        self.df: pd.DataFrame | None = None

    def log(self, message: str) -> None:
        print(message)

    @staticmethod
    def normalize_column_name(column_name: str) -> str:
        """
        Normalize a single column name by removing hidden/control characters
        and trailing commas.
        (숨은 문자/제어문자와 마지막 꼬리 쉼표를 제거해서 컬럼명을 정규화)
        """
        return (
            str(column_name)
            .replace("\ufeff", "")
            .replace("\x00", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace("\t", "")
            .strip()
            .rstrip(",")
        )

    def ensure_input_exists(self) -> None:
        if not self.config.input_path.exists():
            raise FileNotFoundError(f"Input file not found: {self.config.input_path}")

    def ensure_output_dir(self) -> None:
        self.config.output_dir.mkdir(parents=True, exist_ok=True)

    def load_data(self) -> None:
        self.log("[1/5] Loading curated CSV...")
        self.df = pd.read_csv(
            self.config.input_path,
            sep=self.config.delimiter,
            encoding=self.config.encoding,
            dtype=str,
            low_memory=False,
        ).fillna("")

        # Aggressive column normalization
        # (BOM, null char, 개행, 탭, 공백, 마지막 쉼표 제거)
        self.df.columns = [self.normalize_column_name(col) for col in self.df.columns]

        if self.config.debug_columns:
            print("\nNormalized column names (정규화된 컬럼명):")
            print([repr(col) for col in self.df.columns])

    def validate_columns(self) -> None:
        self.log("[2/5] Validating required columns...")
        assert self.df is not None

        normalized_columns = {self.normalize_column_name(col) for col in self.df.columns}
        missing = [col for col in self.REQUIRED_COLUMNS if col not in normalized_columns]

        if missing:
            print("\nDetected columns after normalization:")
            print([repr(col) for col in self.df.columns])
            raise ValueError(f"Missing required columns: {', '.join(missing)}")

    def print_summary(self) -> None:
        self.log("[3/5] Printing inspection summary...")
        assert self.df is not None
        df = self.df

        null_route = df[df["전환_노선ID"] == ""]
        null_boarding_ars = df[df["승차_정류장ARS"] == ""]
        null_alighting_ars = df[df["하차_정류장ARS"] == ""]
        null_boarding_code = df[df["승차_정류장표준코드"] == ""]
        null_alighting_code = df[df["하차_정류장표준코드"] == ""]

        print("\nOverall Summary")
        print("-" * 72)
        print(f"Total rows: {len(df):,}")
        print(f"Null route IDs: {len(null_route):,}")
        print(f"Null boarding ARS: {len(null_boarding_ars):,}")
        print(f"Null alighting ARS: {len(null_alighting_ars):,}")
        print(f"Null boarding standard codes: {len(null_boarding_code):,}")
        print(f"Null alighting standard codes: {len(null_alighting_code):,}")

        print("\nNull Route ID - route name distribution")
        print("-" * 72)
        if len(null_route) == 0:
            print("No null route IDs.")
        else:
            print(null_route["노선명"].value_counts().head(30))

        print("\nNull Boarding ARS - route name distribution")
        print("-" * 72)
        if len(null_boarding_ars) == 0:
            print("No null boarding ARS.")
        else:
            print(null_boarding_ars["노선명"].value_counts().head(30))

        print("\nNull Alighting ARS - route name distribution")
        print("-" * 72)
        if len(null_alighting_ars) == 0:
            print("No null alighting ARS.")
        else:
            print(null_alighting_ars["노선명"].value_counts().head(30))

        print("\nNull Boarding Standard Code - route name distribution")
        print("-" * 72)
        if len(null_boarding_code) == 0:
            print("No null boarding standard codes.")
        else:
            print(null_boarding_code["노선명"].value_counts().head(30))

        print("\nNull Alighting Standard Code - route name distribution")
        print("-" * 72)
        if len(null_alighting_code) == 0:
            print("No null alighting standard codes.")
        else:
            print(null_alighting_code["노선명"].value_counts().head(30))

    def export_review_files(self) -> None:
        self.log("[4/5] Exporting review CSV files...")
        assert self.df is not None
        df = self.df

        review_specs = {
            "null_route_ids.csv": df[df["전환_노선ID"] == ""],
            "null_boarding_ars.csv": df[df["승차_정류장ARS"] == ""],
            "null_alighting_ars.csv": df[df["하차_정류장ARS"] == ""],
            "null_boarding_standard_codes.csv": df[df["승차_정류장표준코드"] == ""],
            "null_alighting_standard_codes.csv": df[df["하차_정류장표준코드"] == ""],
        }

        for file_name, subset in review_specs.items():
            subset.to_csv(
                self.config.output_dir / file_name,
                sep=self.config.delimiter,
                index=False,
                encoding=self.config.encoding,
            )

        if len(review_specs["null_route_ids.csv"]) > 0:
            route_summary = (
                review_specs["null_route_ids.csv"]["노선명"]
                .value_counts()
                .rename_axis("노선명")
                .reset_index(name="count")
            )
            route_summary.to_csv(
                self.config.output_dir / "null_route_id_route_name_counts.csv",
                sep=self.config.delimiter,
                index=False,
                encoding=self.config.encoding,
            )

        if len(review_specs["null_boarding_standard_codes.csv"]) > 0:
            boarding_code_summary = (
                review_specs["null_boarding_standard_codes.csv"]["노선명"]
                .value_counts()
                .rename_axis("노선명")
                .reset_index(name="count")
            )
            boarding_code_summary.to_csv(
                self.config.output_dir / "null_boarding_standard_code_route_name_counts.csv",
                sep=self.config.delimiter,
                index=False,
                encoding=self.config.encoding,
            )

        if len(review_specs["null_alighting_standard_codes.csv"]) > 0:
            alighting_code_summary = (
                review_specs["null_alighting_standard_codes.csv"]["노선명"]
                .value_counts()
                .rename_axis("노선명")
                .reset_index(name="count")
            )
            alighting_code_summary.to_csv(
                self.config.output_dir / "null_alighting_standard_code_route_name_counts.csv",
                sep=self.config.delimiter,
                index=False,
                encoding=self.config.encoding,
            )

    def print_next_actions(self) -> None:
        self.log("[5/5] Printing suggested next actions...")
        print("\nSuggested review order")
        print("-" * 72)
        print("1. Check null_route_ids.csv first.")
        print("2. Review null_route_id_route_name_counts.csv for dominant route names.")
        print("3. Then inspect null_boarding/alighting_standard_codes.csv.")
        print("4. If ARS is blank in raw data, treat it as source-data quality issue.")
        print("5. If route IDs are blank but ARS/name/sequence exist, improve route matching logic or reference coverage.")

    def run(self) -> None:
        self.ensure_input_exists()
        self.ensure_output_dir()
        self.load_data()
        self.validate_columns()
        self.print_summary()
        self.export_review_files()
        self.print_next_actions()


def parse_args() -> InspectionConfig:
    parser = argparse.ArgumentParser(
        description="Inspect curated OD output"
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to curated output CSV",
    )
    parser.add_argument(
        "--output-dir",
        required=True,
        help="Directory to save inspection result files",
    )
    parser.add_argument(
        "--delimiter",
        required=False,
        default="|",
        help="CSV delimiter",
    )
    parser.add_argument(
        "--encoding",
        required=False,
        default="utf-8-sig",
        help="CSV encoding",
    )
    parser.add_argument(
        "--debug-columns",
        action="store_true",
        help="Print normalized column names for debugging",
    )

    args = parser.parse_args()

    return InspectionConfig(
        input_path=Path(args.input),
        output_dir=Path(args.output_dir),
        delimiter=args.delimiter,
        encoding=args.encoding,
        debug_columns=args.debug_columns,
    )


def main() -> None:
    config = parse_args()
    inspector = CuratedOutputInspector(config)
    inspector.run()


if __name__ == "__main__":
    main()
