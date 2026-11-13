"""Typical-day inputs of the hourly OD allocation (estimate_hourly_od --source typical)."""
import pytest

from pipelines.hourly_od_estimation import estimate_hourly_od as od
from pipelines.hourly_od_estimation import validate_rebuild as validate


def profile(peak_hour):
    ratios = [0.0] * 24
    ratios[peak_hour] = 1.0
    return ratios


def test_labels_cover_a_contiguous_set_of_day_types():
    assert od.typical_labels(["weekday"]) == ("typical_weekday", "typical_weekday")
    assert od.typical_labels(["weekday", "saturday", "sunday_holiday"]) == ("typical_saturday", "typical_weekday")
    assert od.typical_labels(["sunday_holiday", "weekday"]) == ("typical_sunday_holiday", "typical_weekday")
    with pytest.raises(SystemExit):
        od.typical_labels(["saturday", "weekday"])      # the range would take sunday_holiday too
    with pytest.raises(SystemExit):
        od.typical_labels(["holiday"])


def test_labels_map_to_ratio_day_types_and_dates_still_work():
    assert od.ratio_day_type("typical_weekday", set()) == "weekday"
    assert od.ratio_day_type("typical_saturday", set()) == "sat"
    assert od.ratio_day_type("typical_sunday_holiday", set()) == "sun_holiday"
    assert od.ratio_day_type("20260915", set()) == "tue"


def test_weekday_profile_is_the_mean_of_fitted_monday_to_friday():
    ratio_map = {("100", "01041", "mon"): profile(7), ("100", "01041", "tue"): profile(8),
                 ("100", "01041", "sat"): profile(12)}
    zero_keys = {("100", "01041", "wed"), ("200", "02006", "thu")}
    added = od.add_weekday_mean(ratio_map, zero_keys)
    assert added == 1
    mean = ratio_map[("100", "01041", "weekday")]
    assert mean[7] == pytest.approx(0.5) and mean[8] == pytest.approx(0.5) and sum(mean) == pytest.approx(1.0)
    # A key whose weekday fits all collapsed keeps the zero-fit fallback, not "no key".
    assert ("200", "02006", "weekday") in zero_keys
    assert od.select_profile(("200", "02006", "weekday"), ratio_map, zero_keys)[1] == od.METHOD_FALLBACK_ZERO_FIT
    assert od.select_profile(("300", "03000", "weekday"), ratio_map, zero_keys)[1] == od.METHOD_FALLBACK_NO_KEY


def test_estimate_spreads_a_typical_row_over_24_hours():
    import pandas as pd
    ratio_map = {("100", "01041", "weekday"): profile(8)}
    daily = pd.DataFrame({"기준일자": ["typical_weekday"], "노선명": ["100"], "승차_정류장ars": ["1041"],
                          "하차_정류장ars": ["02006"], "daily_passengers": [12.5]})
    written = []
    total = od.estimate(None, daily, ratio_map, set(), set(), 1000, writer=written.extend)
    assert total == 24
    assert {r["기준일자"] for r in written} == {"typical_weekday"}
    assert sum(r["estimated_passengers"] for r in written) == pytest.approx(12.5)
    assert written[8]["estimated_passengers"] == pytest.approx(12.5)


def test_validation_reads_the_typical_source_when_asked(monkeypatch):
    seen = []

    class Conn:
        def execute(self, statement, params=None):
            seen.append(str(statement))
            raise StopIteration

    monkeypatch.setattr(validate, "OD_SOURCE_TABLE", validate.OD_SOURCE_TABLE)
    monkeypatch.setattr(validate, "OD_SOURCE_EXTRA_FILTER", validate.OD_SOURCE_EXTRA_FILTER)
    validate.use_typical_source()
    with pytest.raises(StopIteration):
        validate.check_od_totals(Conn(), validate.Report(), "typical_weekday", "typical_weekday")
    assert "FROM od_typical_route_stop" in seen[0]
    assert "NOT LIKE 'name:" in seen[0]
