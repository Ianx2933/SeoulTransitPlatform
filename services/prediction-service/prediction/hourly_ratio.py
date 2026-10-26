class HourlyRatioProvider:
    """
    Provides hourly boarding/alighting distribution ratios.
    This is a baseline hourly decomposition layer. XGBoost predicts daily demand,
    and this provider distributes that daily total into 24 hourly values.
    LSTM can later replace or improve these static ratios when enough hourly
    training data is available.
    """

    # Baseline boarding distribution used before route-stop-specific or LSTM ratios are available.
    DEFAULT_BOARDING_RATIO = [
        0.01, 0.005, 0.003, 0.002, 0.01, 0.04,
        0.08, 0.11, 0.09, 0.05, 0.04, 0.04,
        0.05, 0.05, 0.05, 0.06, 0.08, 0.10,
        0.09, 0.06, 0.04, 0.025, 0.015, 0.005
    ]

    # Baseline alighting distribution kept separate because boarding and alighting peaks can differ.
    DEFAULT_ALIGHTING_RATIO = [
        0.005, 0.003, 0.002, 0.002, 0.008, 0.03,
        0.07, 0.10, 0.10, 0.055, 0.045, 0.045,
        0.05, 0.05, 0.05, 0.06, 0.085, 0.105,
        0.095, 0.065, 0.045, 0.03, 0.015, 0.005
    ]

    def __init__(self, hourly_ratio_df):
        self.hourly_ratio_df = hourly_ratio_df

    def distribute(self, daily_prediction: float, route_no: str, ars_no: str) -> list[dict]:
        """
        Converts one daily demand prediction into 24 hourly boarding/alighting values.
        """
        boarding_ratio, alighting_ratio = self._get_ratios(route_no, ars_no)
        boarding_ratio = self._normalize_ratios(boarding_ratio)
        alighting_ratio = self._normalize_ratios(alighting_ratio)

        return [
            {
                "hour": h,
                "boarding": round(daily_prediction * boarding_ratio[h], 2),
                "alighting": round(daily_prediction * alighting_ratio[h], 2),
            }
            for h in range(24)
        ]


    @staticmethod
    def _normalize_ratios(ratios: list[float]) -> list[float]:
        """Return a non-negative distribution that sums to one.

        Daily OD passengers imply one boarding and one alighting event per
        passenger. Baseline or future route-specific hourly weights therefore
        must not inflate the daily prediction when expanded to 24 hours.
        """
        if len(ratios) != 24:
            raise ValueError("hourly ratio must contain 24 values")
        if any(value < 0 for value in ratios):
            raise ValueError("hourly ratio must not contain negative values")

        total = sum(ratios)
        if total <= 0:
            raise ValueError("hourly ratio sum must be positive")

        return [value / total for value in ratios]

    def _get_ratios(self, route_no: str, ars_no: str) -> tuple[list[float], list[float]]:
        """
        Returns baseline ratios when route-stop-specific hourly ratios are unavailable.

        This keeps the prediction API usable before LSTM/hourly-ratio training is complete.
        """
        return self.DEFAULT_BOARDING_RATIO, self.DEFAULT_ALIGHTING_RATIO
