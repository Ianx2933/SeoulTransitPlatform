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
    # Pipe-separated export of bus_stop_location with columns 정류장번호, 노드id.
    # When given, it is the first source of standard codes and the output is
    # checked against it.
    stop_master_path: Path | None = None


def is_placeholder_ars(series: pd.Series) -> pd.Series:
    """True where the ARS is missing or all zeros.

    00000 marks a stop with no ARS number (depots and other virtual stops), so
    two rows that both carry it are not the same stop.
    """
    s = series.fillna("").astype(str).str.strip().str.replace(r"\.0$", "", regex=True)
    return s.eq("") | s.str.fullmatch(r"0+")


def ars5(series: pd.Series) -> pd.Series:
    """ARS as a 5-digit string, or "" when missing or a placeholder. Join key only."""
    s = series.fillna("").astype(str).str.strip().str.replace(r"\.0$", "", regex=True)
    s = s.where(~is_placeholder_ars(s), "")
    return s.where(s.eq(""), s.str.zfill(5))


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
        self.stop_master: dict[str, str] | None = None

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

        if self.config.stop_master_path is not None:
            master = pd.read_csv(
                self.config.stop_master_path,
                sep="|",
                encoding="utf-8-sig",
                dtype=str,
            )
            self.stop_master = self.build_stop_master(master)
            self.log(f"Stop master: {len(self.stop_master):,} unambiguous ARS")
        else:
            self.log("WARNING: no --stop-master given; codes come from the reference only")

    @staticmethod
    def build_stop_master(master: pd.DataFrame) -> dict[str, str]:
        """ARS -> node ID, keeping only ARS with exactly one distinct node."""
        df = pd.DataFrame({
            "ars": ars5(master["정류장번호"]),
            "node": master["노드id"].fillna("").astype(str).str.strip(),
        })
        df = df[df["ars"].ne("") & df["node"].ne("")].drop_duplicates()
        counts = df.groupby("ars")["node"].transform("nunique")
        return dict(df.loc[counts.eq(1), ["ars", "node"]].drop_duplicates("ars").values)

    @staticmethod
    def normalize_date(series: pd.Series) -> pd.Series:
        parsed = pd.to_datetime(series, format="%y/%m/%d", errors="coerce")
        return parsed.dt.strftime("%y%m%d")

    def normalize_route_name(self, series: pd.Series) -> pd.Series:
        """
        Normalize route names as trimmed strings, then repair known corrupted
        route labels using an override table.
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
        """Fill standard codes, first from the stop master by ARS, then from
        the reference only where the reference has the same ARS at that
        route position.

        The reference used to be joined on (route ID, sequence) alone. When a
        route gained or lost stops after the reference was made, every later
        position took the code of a stop two or four places along. Requiring
        the ARS to match turns that into "no code" instead of a wrong code.
        """
        assert self.raw_df is not None
        assert self.reference_df is not None

        df = self.raw_df.copy()

        for side in ("승차", "하차"):
            ars_col = f"{side}_정류장ARS"
            seq_col = f"{side}_정류장순번"
            code_col = f"{side}_정류장표준코드"
            key_col = f"_{side}_ars5"

            df[key_col] = ars5(df[ars_col])
            df[code_col] = df[code_col].fillna("").astype(str)

            if self.stop_master:
                from_master = df[key_col].map(self.stop_master).fillna("")
                df[code_col] = df[code_col].where(df[code_col].ne(""), from_master)

            ref = self.reference_df[["전환_노선ID", seq_col, code_col]].copy()
            ref[key_col] = ars5(self.reference_df[ars_col])
            ref = ref[ref[key_col].ne("")]
            lookup = self._build_unambiguous_lookup(
                ref, ["전환_노선ID", seq_col, key_col], code_col
            ).rename(columns={code_col: f"{code_col}_ref"})

            df = df.merge(lookup, on=["전환_노선ID", seq_col, key_col], how="left")
            ref_code = df[f"{code_col}_ref"].fillna("").astype(str)
            df[code_col] = df[code_col].where(df[code_col].ne(""), ref_code)
            df.drop(columns=[f"{code_col}_ref", key_col], inplace=True)

            df[code_col] = self.normalize_standard_code(df[code_col])

        self.raw_df = df

    @staticmethod
    def _build_unambiguous_lookup(
        df: pd.DataFrame,
        key_cols: str | list[str],
        code_col: str,
    ) -> pd.DataFrame:
        """Build a key-to-code lookup only when the mapping is one-to-one.

        A fallback must never pick an arbitrary code from an ambiguous key.
        This helper intentionally drops keys associated with two or more
        distinct non-empty codes, leaving those rows unresolved for a weaker
        stage or for explicit inspection.
        """
        keys = [key_cols] if isinstance(key_cols, str) else list(key_cols)

        candidates = (
            df.loc[df[code_col].fillna("").ne(""), keys + [code_col]]
            .dropna(subset=keys)
            .drop_duplicates()
        )
        if candidates.empty:
            return candidates

        candidate_counts = (
            candidates.groupby(keys, dropna=False)[code_col]
            .nunique()
            .reset_index(name="_candidate_count")
        )
        unique_keys = candidate_counts.loc[
            candidate_counts["_candidate_count"].eq(1),
            keys,
        ]

        return (
            candidates.merge(unique_keys, on=keys, how="inner")
            .drop_duplicates(subset=keys)
        )

    @staticmethod
    def _fill_by_key(df: pd.DataFrame, key_col: str, code_col: str) -> pd.DataFrame:
        lookup = ODCorrectionPipeline._build_unambiguous_lookup(df, key_col, code_col)
        # A placeholder ARS joins unrelated stops; never fill through it.
        lookup = lookup[~is_placeholder_ars(lookup[key_col])]

        result = df.merge(lookup, on=key_col, how="left", suffixes=("", "_fill"))
        result[code_col] = result[code_col].replace("", pd.NA).astype("string")
        fill_col = result[f"{code_col}_fill"].astype("string")
        result[code_col] = result[code_col].combine_first(fill_col).fillna("")
        result.drop(columns=[f"{code_col}_fill"], inplace=True)
        return result

    @staticmethod
    def _fill_by_name(
        df: pd.DataFrame, name_col: str, code_col: str, ars_col: str | None = None
    ) -> pd.DataFrame:
        """Fill by stop name; with ars_col, only rows that have no ARS.

        Different stops share names (several 서울역 stops, for one), so a row
        with an ARS of its own must never borrow another stop's code by name.
        The pipeline always passes ars_col. Without it every empty row is
        eligible, as before.
        """
        lookup = ODCorrectionPipeline._build_unambiguous_lookup(df, name_col, code_col)

        result = df.merge(lookup, on=name_col, how="left", suffixes=("", "_fill"))
        if ars_col is None:
            no_ars = pd.Series(True, index=result.index)
        else:
            no_ars = is_placeholder_ars(result[ars_col])
        result[code_col] = result[code_col].replace("", pd.NA).astype("string")
        fill_col = result[f"{code_col}_fill"].astype("string").where(no_ars)
        result[code_col] = result[code_col].combine_first(fill_col).fillna("")
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
        lookup = ODCorrectionPipeline._build_unambiguous_lookup(
            df,
            source_ars_col,
            source_code_col,
        ).rename(columns={
            source_ars_col: target_ars_col,
            source_code_col: f"{target_code_col}_fill",
        })
        lookup = lookup[~is_placeholder_ars(lookup[target_ars_col])]

        result = df.merge(lookup, on=target_ars_col, how="left")
        result[target_code_col] = result[target_code_col].replace("", pd.NA).astype("string")
        fill_col = result[f"{target_code_col}_fill"].astype("string")
        result[target_code_col] = result[target_code_col].combine_first(fill_col).fillna("")
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

        df = self._fill_by_name(self.raw_df, "승차_정류장명", "승차_정류장표준코드", "승차_정류장ARS")
        df = self._fill_by_name(df, "하차_정류장명", "하차_정류장표준코드", "하차_정류장ARS")

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

        self.check_codes_against_master(df)

    def check_codes_against_master(self, df: pd.DataFrame) -> None:
        """Fail when a row's code disagrees with the stop master for its ARS.

        Rows whose ARS is not in the master (or is ambiguous there) cannot be
        checked and are only counted.
        """
        print("\nStop master check (정류장 마스터 대조)")
        print("-" * 70)
        if not self.stop_master:
            print("skipped: no stop master")
            return

        failures = []
        for side in ("승차", "하차"):
            key = ars5(df[f"{side}_정류장ARS"])
            expected = key.map(self.stop_master)
            code = df[f"{side}_정류장표준코드"].fillna("").astype(str)
            checked = expected.notna() & code.ne("")
            wrong = int((checked & code.ne(expected)).sum())
            print(
                f"{side}: checked {int(checked.sum()):,}, wrong {wrong:,}, "
                f"not in master {int(expected.isna().sum()):,}"
            )
            if wrong:
                failures.append(f"{side} {wrong:,}")

        if failures:
            raise ValueError(
                "Standard codes disagree with the stop master: " + ", ".join(failures)
            )

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
    parser.add_argument(
        "--stop-master",
        required=False,
        default=None,
        help="Pipe-separated stop master (정류장번호, 노드id); see STOP_MASTER.md. Required.",
    )
    parser.add_argument(
        "--allow-no-stop-master",
        action="store_true",
        help="Run without the stop master check. Codes then come from the reference only.",
    )

    args = parser.parse_args()

    if not args.stop_master and not args.allow_no_stop_master:
        parser.error(
            "--stop-master is required. Without it standard codes are not checked "
            "against the stop master. Export one as described in STOP_MASTER.md, "
            "or pass --allow-no-stop-master to run unchecked."
        )

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
        stop_master_path=Path(args.stop_master) if args.stop_master else None,
    )


def main() -> None:
    config = parse_args()
    pipeline = ODCorrectionPipeline(config)
    pipeline.run()


if __name__ == "__main__":
    main()
