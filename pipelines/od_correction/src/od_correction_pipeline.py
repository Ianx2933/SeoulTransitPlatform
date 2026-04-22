"""
OD Correction Pipeline
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import pandas as pd


# =============================================================================
# Configuration model
# =============================================================================

@dataclass
class PipelineConfig:
    """
    Runtime configuration for the OD correction pipeline

    Raw, reference, and output encodings are separated because public raw CSV
    files are often cp949 while internally generated reference/output files are
    usually utf-8-sig.

    Raw, reference, and output delimiters are also separated because external
    raw files may be comma-separated while internal reference/output files use
    pipe-separated format.
    """
    raw_path: Path
    reference_path: Path
    output_path: Path
    raw_delimiter: str = ","
    reference_delimiter: str = "|"
    output_delimiter: str = "|"
    raw_encoding: str = "cp949"
    reference_encoding: str = "utf-8-sig"
    output_encoding: str = "utf-8-sig"


class ODCorrectionPipeline:
    """
    Reference-based OD correction pipeline
    """

    RAW_REQUIRED_COLUMNS = [
        "기준일자",
        "노선명",
        "승차_정류장ARS",
        "승차_정류장명",
        "하차_정류장ARS",
        "하차_정류장명",
        "승차_정류장순번",
        "하차_정류장순번",
        "승객수",
    ]

    REFERENCE_REQUIRED_COLUMNS = [
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

    OUTPUT_COLUMNS = [
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

    TOTAL_STEPS = 12

    # Route name repair map for values corrupted by Excel/manual formatting
    # (엑셀/수동 편집으로 깨진 노선명 복원 규칙)
    ROUTE_NAME_OVERRIDES = {
        "411": "0411",
        "40": "040",
        "Jan-01": "101",
    }

    def __init__(self, config: PipelineConfig) -> None:
        self.config = config
        self.raw_df: pd.DataFrame | None = None
        self.reference_df: pd.DataFrame | None = None
        self.curated_df: pd.DataFrame | None = None

    def log(self, message: str) -> None:
        print(message)

    def log_step(self, step_number: int, message: str) -> None:
        print(f"[{step_number}/{self.TOTAL_STEPS}] {message}")

    def ensure_inputs_exist(self) -> None:
        if not self.config.raw_path.exists():
            raise FileNotFoundError(f"Raw input file not found: {self.config.raw_path}")
        if not self.config.reference_path.exists():
            raise FileNotFoundError(f"Reference file not found: {self.config.reference_path}")

    def ensure_output_directory(self) -> None:
        self.config.output_path.parent.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def validate_required_columns(
        df: pd.DataFrame,
        required_columns: Iterable[str],
        dataset_name: str,
    ) -> None:
        missing = [col for col in required_columns if col not in df.columns]
        if missing:
            raise ValueError(
                f"Missing required columns in {dataset_name}: {', '.join(missing)}"
            )

    def load_data(self) -> None:
        self.raw_df = pd.read_csv(
            self.config.raw_path,
            sep=self.config.raw_delimiter,
            encoding=self.config.raw_encoding,
            dtype={"노선명": str},
            low_memory=False,
        )

        self.reference_df = pd.read_csv(
            self.config.reference_path,
            sep=self.config.reference_delimiter,
            encoding=self.config.reference_encoding,
            dtype={"노선명": str, "전환_노선ID": str},
            low_memory=False,
        )

    @staticmethod
    def normalize_date(series: pd.Series) -> pd.Series:
        parsed = pd.to_datetime(series, format="%y/%m/%d", errors="coerce")
        return parsed.dt.strftime("%y%m%d")

    def normalize_route_name(self, series: pd.Series) -> pd.Series:
        """
        Normalize route names as trimmed strings, then repair known corrupted
        route labels using an override table.
        (노선명을 문자열로 정리한 뒤, 확인된 깨진 노선명을 예외 테이블로 복원)
        """
        cleaned = series.fillna("").astype(str).str.strip()
        return cleaned.replace(self.ROUTE_NAME_OVERRIDES)

    @staticmethod
    def normalize_route_id(series: pd.Series) -> pd.Series:
        return (
            series.fillna("")
            .astype(str)
            .str.replace(".0", "", regex=False)
            .str.strip()
        )

    @staticmethod
    def normalize_ars(series: pd.Series) -> pd.Series:
        return (
            series.fillna("")
            .astype(str)
            .str.replace(".0", "", regex=False)
            .str.strip()
        )

    @staticmethod
    def normalize_standard_code(series: pd.Series) -> pd.Series:
        return (
            series.fillna("")
            .astype(str)
            .str.replace(".0", "", regex=False)
            .str.strip()
        )

    @staticmethod
    def normalize_sequence(series: pd.Series) -> pd.Series:
        return pd.to_numeric(series, errors="coerce").astype("Int64")

    @staticmethod
    def normalize_passenger_count(series: pd.Series) -> pd.Series:
        return pd.to_numeric(series, errors="coerce").fillna(0).astype(int)

    def normalize_raw(self) -> None:
        assert self.raw_df is not None

        self.validate_required_columns(self.raw_df, self.RAW_REQUIRED_COLUMNS, "raw input")

        df = self.raw_df.copy()
        df.columns = df.columns.str.strip()

        df["기준일자"] = self.normalize_date(df["기준일자"])
        df["노선명"] = self.normalize_route_name(df["노선명"])

        df["승차_정류장ARS"] = self.normalize_ars(df["승차_정류장ARS"])
        df["하차_정류장ARS"] = self.normalize_ars(df["하차_정류장ARS"])

        df["승차_정류장순번"] = self.normalize_sequence(df["승차_정류장순번"])
        df["하차_정류장순번"] = self.normalize_sequence(df["하차_정류장순번"])

        df["승객수"] = self.normalize_passenger_count(df["승객수"])

        df["전환_노선ID"] = ""
        df["승차_정류장표준코드"] = ""
        df["하차_정류장표준코드"] = ""

        self.raw_df = df

    def normalize_reference(self) -> None:
        assert self.reference_df is not None

        self.validate_required_columns(
            self.reference_df,
            self.REFERENCE_REQUIRED_COLUMNS,
            "reference input",
        )

        df = self.reference_df.copy()
        df.columns = df.columns.str.strip()

        df["기준일자"] = self.normalize_date(df["기준일자"])
        df["노선명"] = self.normalize_route_name(df["노선명"])
        df["전환_노선ID"] = self.normalize_route_id(df["전환_노선ID"])

        df["승차_정류장ARS"] = self.normalize_ars(df["승차_정류장ARS"])
        df["하차_정류장ARS"] = self.normalize_ars(df["하차_정류장ARS"])

        df["승차_정류장표준코드"] = self.normalize_standard_code(df["승차_정류장표준코드"])
        df["하차_정류장표준코드"] = self.normalize_standard_code(df["하차_정류장표준코드"])

        df["승차_정류장순번"] = self.normalize_sequence(df["승차_정류장순번"])
        df["하차_정류장순번"] = self.normalize_sequence(df["하차_정류장순번"])

        df["승객수"] = self.normalize_passenger_count(df["승객수"])

        self.reference_df = df

    def map_route_ids(self) -> None:
        assert self.raw_df is not None
        assert self.reference_df is not None

        df = self.raw_df.copy()

        boarding_route_lookup = (
            self.reference_df[["노선명", "승차_정류장순번", "전환_노선ID"]]
            .dropna(subset=["전환_노선ID"])
            .drop_duplicates(subset=["노선명", "승차_정류장순번"])
            .rename(
                columns={
                    "승차_정류장순번": "승차_정류장순번_lookup",
                    "전환_노선ID": "전환_노선ID_boarding",
                }
            )
        )

        df = df.merge(
            boarding_route_lookup,
            left_on=["노선명", "승차_정류장순번"],
            right_on=["노선명", "승차_정류장순번_lookup"],
            how="left",
        )

        df["전환_노선ID"] = df["전환_노선ID"].replace("", pd.NA)
        df["전환_노선ID"] = df["전환_노선ID"].fillna(df["전환_노선ID_boarding"])
        df.drop(columns=["승차_정류장순번_lookup", "전환_노선ID_boarding"], inplace=True)

        alighting_route_lookup = (
            self.reference_df[["노선명", "하차_정류장순번", "전환_노선ID"]]
            .dropna(subset=["전환_노선ID"])
            .drop_duplicates(subset=["노선명", "하차_정류장순번"])
            .rename(
                columns={
                    "하차_정류장순번": "하차_정류장순번_lookup",
                    "전환_노선ID": "전환_노선ID_alighting",
                }
            )
        )

        df = df.merge(
            alighting_route_lookup,
            left_on=["노선명", "하차_정류장순번"],
            right_on=["노선명", "하차_정류장순번_lookup"],
            how="left",
        )

        df["전환_노선ID"] = df["전환_노선ID"].fillna(df["전환_노선ID_alighting"])
        df.drop(columns=["하차_정류장순번_lookup", "전환_노선ID_alighting"], inplace=True)

        df["전환_노선ID"] = self.normalize_route_id(df["전환_노선ID"])

        self.raw_df = df

    def map_standard_codes(self) -> None:
        assert self.raw_df is not None
        assert self.reference_df is not None

        boarding_lookup = (
            self.reference_df[["전환_노선ID", "승차_정류장순번", "승차_정류장표준코드"]]
            .drop_duplicates(subset=["전환_노선ID", "승차_정류장순번"])
        )

        alighting_lookup = (
            self.reference_df[["전환_노선ID", "하차_정류장순번", "하차_정류장표준코드"]]
            .drop_duplicates(subset=["전환_노선ID", "하차_정류장순번"])
        )

        df = self.raw_df.merge(
            boarding_lookup,
            on=["전환_노선ID", "승차_정류장순번"],
            how="left",
            suffixes=("", "_ref"),
        )

        df["승차_정류장표준코드"] = df["승차_정류장표준코드"].replace("", pd.NA)
        df["승차_정류장표준코드"] = df["승차_정류장표준코드"].fillna(df["승차_정류장표준코드_ref"])
        df.drop(columns=["승차_정류장표준코드_ref"], inplace=True)

        df = df.merge(
            alighting_lookup,
            on=["전환_노선ID", "하차_정류장순번"],
            how="left",
            suffixes=("", "_ref"),
        )

        df["하차_정류장표준코드"] = df["하차_정류장표준코드"].replace("", pd.NA)
        df["하차_정류장표준코드"] = df["하차_정류장표준코드"].fillna(df["하차_정류장표준코드_ref"])
        df.drop(columns=["하차_정류장표준코드_ref"], inplace=True)

        df["승차_정류장표준코드"] = self.normalize_standard_code(df["승차_정류장표준코드"])
        df["하차_정류장표준코드"] = self.normalize_standard_code(df["하차_정류장표준코드"])

        self.raw_df = df

    @staticmethod
    def _fill_by_key(df: pd.DataFrame, key_col: str, code_col: str) -> pd.DataFrame:
        lookup = (
            df[df[code_col].ne("")][[key_col, code_col]]
            .drop_duplicates(subset=[key_col])
        )

        result = df.merge(lookup, on=key_col, how="left", suffixes=("", "_fill"))
        result[code_col] = result[code_col].replace("", pd.NA)
        result[code_col] = result[code_col].fillna(result[f"{code_col}_fill"])
        result[code_col] = result[code_col].fillna("")
        result.drop(columns=[f"{code_col}_fill"], inplace=True)
        return result

    @staticmethod
    def _fill_by_name(df: pd.DataFrame, name_col: str, code_col: str) -> pd.DataFrame:
        lookup = (
            df[df[code_col].ne("")][[name_col, code_col]]
            .drop_duplicates(subset=[name_col])
        )

        result = df.merge(lookup, on=name_col, how="left", suffixes=("", "_fill"))
        result[code_col] = result[code_col].replace("", pd.NA)
        result[code_col] = result[code_col].fillna(result[f"{code_col}_fill"])
        result[code_col] = result[code_col].fillna("")
        result.drop(columns=[f"{code_col}_fill"], inplace=True)
        return result

    @staticmethod
    def _fill_by_cross_reference(
        df: pd.DataFrame,
        source_ars_col: str,
        source_code_col: str,
        target_ars_col: str,
        target_code_col: str,
    ) -> pd.DataFrame:
        lookup = (
            df[df[source_code_col].ne("")][[source_ars_col, source_code_col]]
            .rename(columns={
                source_ars_col: target_ars_col,
                source_code_col: f"{target_code_col}_fill",
            })
            .drop_duplicates(subset=[target_ars_col])
        )

        result = df.merge(lookup, on=target_ars_col, how="left")
        result[target_code_col] = result[target_code_col].replace("", pd.NA)
        result[target_code_col] = result[target_code_col].fillna(result[f"{target_code_col}_fill"])
        result[target_code_col] = result[target_code_col].fillna("")
        result.drop(columns=[f"{target_code_col}_fill"], inplace=True)
        return result

    def fill_missing_by_same_ars(self) -> None:
        assert self.raw_df is not None

        df = self._fill_by_key(self.raw_df, "승차_정류장ARS", "승차_정류장표준코드")
        df = self._fill_by_key(df, "하차_정류장ARS", "하차_정류장표준코드")

        df["승차_정류장표준코드"] = self.normalize_standard_code(df["승차_정류장표준코드"])
        df["하차_정류장표준코드"] = self.normalize_standard_code(df["하차_정류장표준코드"])

        self.raw_df = df

    def fill_missing_by_stop_name(self) -> None:
        assert self.raw_df is not None

        df = self._fill_by_name(self.raw_df, "승차_정류장명", "승차_정류장표준코드")
        df = self._fill_by_name(df, "하차_정류장명", "하차_정류장표준코드")

        df["승차_정류장표준코드"] = self.normalize_standard_code(df["승차_정류장표준코드"])
        df["하차_정류장표준코드"] = self.normalize_standard_code(df["하차_정류장표준코드"])

        self.raw_df = df

    def fill_missing_by_cross_reference(self) -> None:
        assert self.raw_df is not None

        df = self._fill_by_cross_reference(
            self.raw_df,
            source_ars_col="승차_정류장ARS",
            source_code_col="승차_정류장표준코드",
            target_ars_col="하차_정류장ARS",
            target_code_col="하차_정류장표준코드",
        )

        df = self._fill_by_cross_reference(
            df,
            source_ars_col="하차_정류장ARS",
            source_code_col="하차_정류장표준코드",
            target_ars_col="승차_정류장ARS",
            target_code_col="승차_정류장표준코드",
        )

        df["승차_정류장표준코드"] = self.normalize_standard_code(df["승차_정류장표준코드"])
        df["하차_정류장표준코드"] = self.normalize_standard_code(df["하차_정류장표준코드"])

        self.raw_df = df

    def finalize_code_formats(self) -> None:
        assert self.raw_df is not None

        df = self.raw_df.copy()

        df["승차_정류장ARS"] = df["승차_정류장ARS"].apply(lambda x: x.zfill(5) if x != "" else "")
        df["하차_정류장ARS"] = df["하차_정류장ARS"].apply(lambda x: x.zfill(5) if x != "" else "")

        df["승차_정류장표준코드"] = df["승차_정류장표준코드"].apply(lambda x: x.zfill(9) if x != "" else "")
        df["하차_정류장표준코드"] = df["하차_정류장표준코드"].apply(lambda x: x.zfill(9) if x != "" else "")

        self.raw_df = df

    def build_output(self) -> None:
        assert self.raw_df is not None
        self.curated_df = self.raw_df[self.OUTPUT_COLUMNS].copy()

    def validate_output(self) -> None:
        assert self.curated_df is not None

        df = self.curated_df
        self.validate_required_columns(df, self.OUTPUT_COLUMNS, "curated output")

        print("\nData Quality Summary (데이터 품질 요약)")
        print("-" * 70)
        print(f"Total rows (총 행 수): {len(df):,}")
        print(f"Null route IDs (전환 노선ID 결측): {df['전환_노선ID'].eq('').sum():,}")
        print(f"Null boarding ARS (승차 ARS 결측): {df['승차_정류장ARS'].eq('').sum():,}")
        print(f"Null alighting ARS (하차 ARS 결측): {df['하차_정류장ARS'].eq('').sum():,}")
        print(f"Null boarding standard codes (승차 표준코드 결측): {df['승차_정류장표준코드'].eq('').sum():,}")
        print(f"Null alighting standard codes (하차 표준코드 결측): {df['하차_정류장표준코드'].eq('').sum():,}")

        print("\nLength checks (길이 검증)")
        print("-" * 70)
        print("Boarding ARS length distribution (승차 ARS 길이 분포):")
        print(df["승차_정류장ARS"].str.len().value_counts(dropna=False).sort_index())

        print("\nAlighting ARS length distribution (하차 ARS 길이 분포):")
        print(df["하차_정류장ARS"].str.len().value_counts(dropna=False).sort_index())

        print("\nBoarding standard code length distribution (승차 표준코드 길이 분포):")
        print(df["승차_정류장표준코드"].str.len().value_counts(dropna=False).sort_index())

        print("\nAlighting standard code length distribution (하차 표준코드 길이 분포):")
        print(df["하차_정류장표준코드"].str.len().value_counts(dropna=False).sort_index())

    def save_output(self) -> None:
        assert self.curated_df is not None

        self.ensure_output_directory()

        self.curated_df.to_csv(
            self.config.output_path,
            sep=self.config.output_delimiter,
            index=False,
            encoding=self.config.output_encoding,
        )

        print(f"\nCurated output saved to (curated 출력 저장 위치): {self.config.output_path}")

    def run(self) -> None:
        self.log("=== OD Correction Pipeline Start ===")

        self.ensure_inputs_exist()
        self.log_step(1, "Loading raw and reference CSV files...")
        self.load_data()

        self.log_step(2, "Normalizing raw input...")
        self.normalize_raw()

        self.log_step(3, "Normalizing reference input...")
        self.normalize_reference()

        self.log_step(4, "Applying route name overrides and mapping converted route IDs...")
        self.map_route_ids()

        self.log_step(5, "Mapping standard stop codes...")
        self.map_standard_codes()

        self.log_step(6, "Filling missing codes by same ARS...")
        self.fill_missing_by_same_ars()

        self.log_step(7, "Filling missing codes by stop name...")
        self.fill_missing_by_stop_name()

        self.log_step(8, "Filling missing codes by cross-reference...")
        self.fill_missing_by_cross_reference()

        self.log_step(9, "Finalizing ARS and standard code formats...")
        self.finalize_code_formats()

        self.log_step(10, "Building curated output...")
        self.build_output()

        self.log_step(11, "Validating curated output...")
        self.validate_output()

        self.log_step(12, "Saving curated output...")
        self.save_output()

        self.log("=== Pipeline Completed Successfully ===")


def parse_args() -> PipelineConfig:
    parser = argparse.ArgumentParser(
        description="Reference-based OD correction pipeline"
    )

    parser.add_argument("--raw", required=True, help="Path to the raw OD CSV file")
    parser.add_argument("--reference", required=True, help="Path to the reference OD CSV file")
    parser.add_argument("--output", required=True, help="Path to the curated output CSV file")
    parser.add_argument("--raw-delimiter", required=False, default=",", help="Raw CSV delimiter")
    parser.add_argument("--reference-delimiter", required=False, default="|", help="Reference CSV delimiter")
    parser.add_argument("--output-delimiter", required=False, default="|", help="Output CSV delimiter")
    parser.add_argument("--raw-encoding", required=False, default="cp949", help="Raw CSV encoding")
    parser.add_argument("--reference-encoding", required=False, default="utf-8-sig", help="Reference CSV encoding")
    parser.add_argument("--output-encoding", required=False, default="utf-8-sig", help="Output CSV encoding")

    args = parser.parse_args()

    return PipelineConfig(
        raw_path=Path(args.raw),
        reference_path=Path(args.reference),
        output_path=Path(args.output),
        raw_delimiter=args.raw_delimiter,
        reference_delimiter=args.reference_delimiter,
        output_delimiter=args.output_delimiter,
        raw_encoding=args.raw_encoding,
        reference_encoding=args.reference_encoding,
        output_encoding=args.output_encoding,
    )


def main() -> None:
    config = parse_args()
    pipeline = ODCorrectionPipeline(config)
    pipeline.run()


if __name__ == "__main__":
    main()
