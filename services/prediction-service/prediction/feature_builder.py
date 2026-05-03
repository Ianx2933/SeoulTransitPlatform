import pandas as pd


class FeatureBuilder:
    """
    EN: Builds XGBoost input features from API payload.
    KR: API payload를 XGBoost 입력 피처로 변환한다.

    EN: Spring provides operational request values such as routeNo, arsNo,
    dayOfWeek, isHoliday, month, prevWeek, and prevMonth. Features that are
    difficult to compute at request time, such as rolling means and route-level
    trends, fall back to training-time defaults saved in feature_defaults.json.
    KR: Spring은 routeNo, arsNo, dayOfWeek, isHoliday, month, prevWeek,
    prevMonth 같은 운영 요청 값을 제공한다. 요청 시점에 계산하기 어려운
    rolling mean, route-level trend 계열 feature는 feature_defaults.json에
    저장된 학습 시점 기본값으로 보정한다.
    """

    REQUIRED_KEYS = [
        "routeNo",
        "arsNo",
        "dayOfWeek",
        "isHoliday",
        "month",
        "prevWeek",
        "prevMonth",
    ]

    def __init__(
        self,
        route_code_map: dict,
        stop_code_map: dict,
        feature_columns: list[str],
        feature_defaults: dict | None = None,
    ):
        self.route_code_map = route_code_map
        self.stop_code_map = stop_code_map
        self.feature_columns = feature_columns
        self.feature_defaults = feature_defaults or {}

    def build(self, payload: dict) -> pd.DataFrame:
        """
        EN: Validates payload and returns a single-row feature DataFrame.
        KR: payload를 검증하고 단일 row feature DataFrame을 반환한다.

        EN: The DataFrame is ordered by feature_columns to match the training schema.
        KR: 반환 DataFrame은 학습 schema와 맞추기 위해 feature_columns 순서를 따른다.
        """
        self._validate_required_keys(payload)

        route_no = self._normalize_route(payload["routeNo"])
        ars_key = self._resolve_ars(payload["arsNo"])

        day_of_week = int(payload["dayOfWeek"])
        is_holiday = int(payload["isHoliday"])
        is_weekend = 1 if day_of_week >= 5 else 0
        day_group = 2 if is_holiday == 1 else (1 if is_weekend == 1 else 0)

        features = {
            "dayOfWeek": day_of_week,
            "isWeekend": is_weekend,
            "dayGroup": day_group,
            "isHoliday": is_holiday,
            "month": int(payload["month"]),
            "routeCode": self.route_code_map[route_no],
            "stopCode": self.stop_code_map[ars_key],
            "prevWeek": float(payload["prevWeek"]),
            "prevMonth": float(payload["prevMonth"]),
        }

        # EN: Optional advanced features may be supplied by Spring later. Until then,
        # use training-time defaults so the improved model can be served safely.
        # KR: 향후 Spring에서 고급 feature를 넘길 수 있다. 그 전까지는 개선 모델을
        # 안전하게 serving하기 위해 학습 시점 기본값을 사용한다.
        for column in self.feature_columns:
            if column in features:
                continue
            if column in payload:
                features[column] = float(payload[column])
            else:
                features[column] = float(self.feature_defaults.get(column, 0.0))

        return pd.DataFrame([{column: features[column] for column in self.feature_columns}])

    def _normalize_route(self, route_no) -> str:
        normalized = str(route_no).strip()
        if normalized not in self.route_code_map:
            raise ValueError(f"Unknown routeNo: {normalized}")
        return normalized

    def _normalize_ars(self, ars_no) -> str:
        normalized = str(ars_no).strip()
        if not normalized.isdigit():
            raise ValueError("arsNo must contain digits only")
        if len(normalized) > 5:
            raise ValueError("arsNo must be at most 5 digits")
        return normalized.zfill(5)

    def _resolve_ars(self, ars_no) -> str:
        padded = self._normalize_ars(ars_no)
        if padded in self.stop_code_map:
            return padded

        stripped = padded.lstrip("0") or "0"
        if stripped in self.stop_code_map:
            return stripped

        raise ValueError(f"Unknown arsNo: {padded}")

    def _validate_required_keys(self, payload: dict) -> None:
        missing = [key for key in self.REQUIRED_KEYS if key not in payload]
        if missing:
            raise ValueError(f"Missing required fields: {missing}")
