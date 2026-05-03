import numpy as np

from prediction.feature_builder import FeatureBuilder
from prediction.hourly_ratio import HourlyRatioProvider
from prediction.model_loader import ModelBundle


class Predictor:
    """
    EN: Runs XGBoost inference and hourly distribution.
    KR: XGBoost 추론과 시간대별 분배를 수행한다.
    """

    def __init__(self):
        self.bundle = ModelBundle()
        self.feature_builder = FeatureBuilder(
            self.bundle.route_code_map,
            self.bundle.stop_code_map,
            self.bundle.feature_columns,
            self.bundle.feature_defaults,
        )
        self.hourly_ratio_provider = HourlyRatioProvider(self.bundle.hourly_ratio)

    def predict(self, payload: dict) -> dict:
        """
        EN: Runs one prediction request.
        KR: 단일 예측 요청을 처리한다.

        EN: The improved model predicts log1p(passenger_count), so its output is
        converted back with expm1 before building the API response.
        KR: 개선 모델은 log1p(passenger_count)를 예측하므로, API 응답 생성 전에
        expm1로 실제 승객 수 단위로 복원한다.
        """
        features = self.feature_builder.build(payload)
        raw_prediction = float(self.bundle.model.predict(features)[0])

        if self.bundle.target_transform == "log1p":
            daily_prediction = float(np.expm1(raw_prediction))
        else:
            daily_prediction = raw_prediction

        daily_prediction = max(daily_prediction, 0.0)

        route_no = str(payload["routeNo"]).strip()
        ars_no = str(payload["arsNo"]).strip().zfill(5)

        return {
            "dailyPrediction": round(daily_prediction, 2),
            "hourlyPrediction": self.hourly_ratio_provider.distribute(daily_prediction, route_no, ars_no),
        }
