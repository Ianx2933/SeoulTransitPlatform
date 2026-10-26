import numpy as np

from prediction.feature_builder import FeatureBuilder
from prediction.hourly_ratio import HourlyRatioProvider
from prediction.model_loader import ModelBundle


class Predictor:
    """
    Runs XGBoost inference and hourly distribution.
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
        Runs one prediction request.
        The improved model predicts log1p(passenger_count), so its output is
        converted back with expm1 before building the API response.
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
