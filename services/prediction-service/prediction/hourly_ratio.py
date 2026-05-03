class HourlyRatioProvider:
    """
    EN: Provides hourly boarding/alighting distribution ratios.
    KR: 시간대별 승차/하차 분배 비율을 제공한다.

    EN: This is a baseline hourly decomposition layer. XGBoost predicts daily demand,
    and this provider distributes that daily total into 24 hourly values.
    KR: 이 클래스는 baseline 시간대 분해 계층이다. XGBoost가 일별 수요를 예측하면,
    이 provider가 그 일별 총량을 24시간 값으로 분배한다.

    EN: LSTM can later replace or improve these static ratios when enough hourly
    training data is available.
    KR: 충분한 시간대 학습 데이터가 확보되면 LSTM이 이 정적 비율을 대체하거나 개선할 수 있다.
    """

    # EN: Baseline boarding distribution used before route-stop-specific or LSTM ratios are available.
    # KR: 노선-정류장별 비율 또는 LSTM 비율이 준비되기 전 사용하는 기본 승차 분포다.
    DEFAULT_BOARDING_RATIO = [
        0.01, 0.005, 0.003, 0.002, 0.01, 0.04,
        0.08, 0.11, 0.09, 0.05, 0.04, 0.04,
        0.05, 0.05, 0.05, 0.06, 0.08, 0.10,
        0.09, 0.06, 0.04, 0.025, 0.015, 0.005
    ]

    # EN: Baseline alighting distribution kept separate because boarding and alighting peaks can differ.
    # KR: 승차와 하차 피크는 다를 수 있으므로 하차 분포를 별도로 둔다.
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
        EN: Converts one daily demand prediction into 24 hourly boarding/alighting values.
        KR: 하나의 일별 수요 예측값을 24시간 승차/하차 값으로 변환한다.
        """
        boarding_ratio, alighting_ratio = self._get_ratios(route_no, ars_no)

        return [
            {
                "hour": h,
                "boarding": round(daily_prediction * boarding_ratio[h], 2),
                "alighting": round(daily_prediction * alighting_ratio[h], 2),
            }
            for h in range(24)
        ]

    def _get_ratios(self, route_no: str, ars_no: str) -> tuple[list[float], list[float]]:
        """
        EN: Returns baseline ratios when route-stop-specific hourly ratios are unavailable.
        KR: 노선-정류장별 시간대 비율이 없을 때 baseline 비율을 반환한다.

        EN: This keeps the prediction API usable before LSTM/hourly-ratio training is complete.
        KR: LSTM/시간대 비율 학습이 완료되기 전에도 예측 API가 동작하도록 한다.
        """
        return self.DEFAULT_BOARDING_RATIO, self.DEFAULT_ALIGHTING_RATIO
