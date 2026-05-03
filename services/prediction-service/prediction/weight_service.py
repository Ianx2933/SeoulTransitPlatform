class WeightService:
    """
    EN: Handles hourly weight update requests.
    KR: 시간대별 가중치 업데이트 요청을 처리한다.

    EN: This service is reserved for operational tuning of hourly patterns and should not
    be mixed with normal prediction requests.
    KR: 이 서비스는 시간대 패턴의 운영상 튜닝을 위한 것이며 일반 예측 요청과 섞지 않는다.

    EN: Current implementation validates requests only. Persistence can be added later
    when user-adjusted weights become part of the service workflow.
    KR: 현재 구현은 요청 검증만 수행한다. 사용자 조정 가중치가 서비스 흐름에 포함되면
    이후 저장 로직을 추가할 수 있다.
    """

    VALID_DAY_TYPES = {"weekday", "saturday", "sunday", "holiday", "평일", "토요일", "일요일", "공휴일"}

    def update(self, payload: dict) -> None:
        """
        EN: Validates weight update payload.
        KR: 가중치 업데이트 payload를 검증한다.

        EN: Validation is intentionally strict because invalid weights can distort all
        hourly prediction results.
        KR: 잘못된 가중치는 전체 시간대 예측 결과를 왜곡할 수 있으므로 엄격하게 검증한다.
        """
        day_type = payload.get("dayType")
        hour = payload.get("hour")
        weight = payload.get("weight")

        if day_type not in self.VALID_DAY_TYPES:
            raise ValueError("Invalid dayType")

        if hour is None or not (0 <= int(hour) <= 23):
            raise ValueError("hour must be between 0 and 23")

        if weight is None or float(weight) < 0:
            raise ValueError("weight must be non-negative")
