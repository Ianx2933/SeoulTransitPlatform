from pathlib import Path
import json

import joblib
import pandas as pd


class ModelBundle:
    """
    EN: Loads and keeps prediction model artifacts in memory.
    KR: 예측 모델 산출물을 메모리에 로드하여 보관한다.
    """

    DEFAULT_FEATURE_COLUMNS = [
        "dayOfWeek",
        "isHoliday",
        "month",
        "routeCode",
        "stopCode",
        "prevWeek",
        "prevMonth",
    ]

    def __init__(self, model_dir: Path | None = None):
        self.model_dir = model_dir or Path(__file__).resolve().parents[1] / "model"

        self.model = joblib.load(self.model_dir / "congestion_model.pkl")
        self.route_code_map = self._normalize_map_keys(joblib.load(self.model_dir / "route_code_map.pkl"))
        self.stop_code_map = self._normalize_stop_map_keys(joblib.load(self.model_dir / "stop_code_map.pkl"))
        self.feature_columns = self._load_feature_columns()
        self.feature_defaults = self._load_feature_defaults()
        self.training_metrics = self._load_training_metrics()
        self.target_transform = self._resolve_target_transform()
        self.hourly_ratio = self._load_hourly_ratio()

    def _load_feature_columns(self) -> list[str]:
        path = self.model_dir / "feature_columns.json"
        if not path.exists():
            return self.DEFAULT_FEATURE_COLUMNS

        with path.open("r", encoding="utf-8") as file:
            return json.load(file)

    def _load_feature_defaults(self) -> dict:
        """
        EN: Loads serving defaults for feature columns added during model improvement.
        KR: 모델 개선 과정에서 추가된 feature column의 serving 기본값을 로드한다.
        """
        path = self.model_dir / "feature_defaults.json"
        if not path.exists():
            return {column: 0.0 for column in self.feature_columns}

        with path.open("r", encoding="utf-8") as file:
            return json.load(file)

    def _load_training_metrics(self) -> dict:
        path = self.model_dir / "training_metrics.json"
        if not path.exists():
            return {}

        with path.open("r", encoding="utf-8") as file:
            return json.load(file)

    def _resolve_target_transform(self) -> str | None:
        """
        EN: Detects whether the model predicts raw passengers or log1p(passengers).
        KR: 모델이 실제 승객 수를 직접 예측하는지 log1p(승객수)를 예측하는지 판별한다.
        """
        return (
            self.feature_defaults.get("targetTransform")
            or self.training_metrics.get("target_transform")
        )

    def _normalize_map_keys(self, mapping: dict) -> dict:
        return {str(key).strip(): value for key, value in mapping.items()}

    def _normalize_stop_map_keys(self, mapping: dict) -> dict:
        normalized = {}

        for key, value in mapping.items():
            raw_key = str(key).strip()
            padded_key = raw_key.zfill(5)
            stripped_key = padded_key.lstrip("0") or "0"

            normalized[padded_key] = value
            normalized[stripped_key] = value

        return normalized

    def _load_hourly_ratio(self) -> pd.DataFrame:
        path = self.model_dir / "hourly_ratio.csv"
        if not path.exists():
            return pd.DataFrame()

        df = pd.read_csv(path, dtype={"버스정류장ars번호": str})

        if "버스정류장ars번호" in df.columns:
            df["버스정류장ars번호"] = df["버스정류장ars번호"].astype(str).str.strip().str.zfill(5)

        return df
