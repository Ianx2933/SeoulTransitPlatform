class WeightService:
    """
    Handles hourly weight update requests.
    This service is reserved for operational tuning of hourly patterns and should not
    be mixed with normal prediction requests.

    Current implementation validates requests only. Persistence can be added later
    when user-adjusted weights become part of the service workflow.
    """

    VALID_DAY_TYPES = {"weekday", "saturday", "sunday", "holiday", "평일", "토요일", "일요일", "공휴일"}

    def update(self, payload: dict) -> None:
        """
        Validates weight update payload.

        Validation is intentionally strict because invalid weights can distort all
        hourly prediction results.
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
