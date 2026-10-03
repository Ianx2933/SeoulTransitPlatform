"""Independent holiday-date and calendar contracts; no database or API calls.

Lunar dates come from a separate calendar implementation rather than the pipeline CSV. Temporary/election/substitute holidays still need official review.
"""
from __future__ import annotations
import calendar
import datetime as dt
from pathlib import Path
import pandas as pd
import pytest
from korean_lunar_calendar import KoreanLunarCalendar
from pipelines.hourly_od_estimation.common import (
    DAY_TYPES, build_month_counts, day_type_for_date, load_holidays,
)

HOLIDAY_CSV = Path("pipelines/hourly_od_estimation/korea_public_holidays_2020_2026.csv")
EXPECTED_YEARS = range(2020, 2027)
FIXED_DATE_HOLIDAYS = {
    (1, 1): "신정", (3, 1): "삼일절", (5, 5): "어린이날", (6, 6): "현충일",
    (8, 15): "광복절", (10, 3): "개천절", (10, 9): "한글날", (12, 25): "성탄절",
}


def lunar_holiday_dates(year: int) -> dict[str, set[str]]:
    result = {}
    for name, month, day, offsets in (
        ("설날", 1, 1, (-1, 0, 1)), ("추석", 8, 15, (-1, 0, 1)),
        ("부처님오신날", 4, 8, (0,)),
    ):
        converter = KoreanLunarCalendar()
        if not converter.setLunarDate(year, month, day, False):
            raise ValueError(f"unsupported lunar year: {year}")
        central = dt.date.fromisoformat(converter.SolarIsoFormat())
        result[name] = {(central + dt.timedelta(days=offset)).isoformat() for offset in offsets}
    return result


def assert_holiday_contract(frame: pd.DataFrame, holidays: set[str], years=EXPECTED_YEARS):
    date_col = next((c for c in ("날짜", "date", "DATE", "일자") if c in frame), None)
    name_col = next((c for c in ("공휴일명", "휴일명", "name", "비고", "구분") if c in frame), None)
    assert date_col is not None, "missing holiday date column"
    assert name_col is not None, "missing holiday name column"
    assert not frame.empty, "empty holiday CSV"
    assert frame[name_col].notna().all(), "NULL holiday name"
    raw = frame[date_col].astype(str).str.strip()
    compact = raw.str.fullmatch(r"\d{8}")
    normalized = raw.where(~compact, raw.str[:4] + "-" + raw.str[4:6] + "-" + raw.str[6:8])
    dates = pd.to_datetime(normalized, format="%Y-%m-%d", errors="coerce")
    assert dates.notna().all(), "unparseable holiday dates"
    date_strings = dates.dt.strftime("%Y-%m-%d")
    assert set(date_strings) == holidays, "CSV parsing differs from load_holidays"
    names = frame[name_col].astype(str).str.replace(" ", "", regex=False)
    missing = []
    wrong = []
    for year in years:
        for month, day in FIXED_DATE_HOLIDAYS:
            value = f"{year}-{month:02d}-{day:02d}"
            if value not in holidays:
                missing.append(value)
        for name, expected in lunar_holiday_dates(year).items():
            missing.extend(sorted(expected - holidays))
            pattern = "부처님오신날|석가탄신일" if name == "부처님오신날" else name
            # Substitute days are outside the base lunar interval by design.
            mask = names.str.contains(pattern, regex=True) & ~names.str.contains("대체")
            actual = set(date_strings[mask & (dates.dt.year == year)])
            wrong.extend(sorted(actual - expected))
    assert not missing, f"missing required holidays: {missing}"
    assert not wrong, f"incorrect named lunar holidays: {wrong}"


@pytest.fixture(scope="module")
def holiday_path():
    path = Path(__file__).resolve().parents[1] / HOLIDAY_CSV
    assert path.is_file(), f"required holiday CSV missing: {path}"
    return path


@pytest.fixture(scope="module")
def holidays(holiday_path):
    return load_holidays(str(holiday_path))


@pytest.fixture(scope="module")
def holiday_frame(holiday_path):
    # Try UTF-8 first; some UTF-8 byte sequences also decode as cp949 incorrectly.
    # (UTF-8을 먼저 시도한다. 일부 UTF-8 바이트열은 cp949로도 오류 없이, 그러나
    #  잘못 해석된다.)
    try:
        return pd.read_csv(holiday_path, encoding="utf-8-sig", dtype=str)
    except UnicodeDecodeError:
        return pd.read_csv(holiday_path, encoding="cp949", dtype=str)


def test_committed_holiday_calendar(holiday_frame, holidays):
    assert_holiday_contract(holiday_frame, holidays)


def valid_frame(year=2024):
    rows = [(f"{year}-{m:02d}-{d:02d}", name) for (m, d), name in FIXED_DATE_HOLIDAYS.items()]
    rows += [(date, name) for name, values in lunar_holiday_dates(year).items() for date in sorted(values)]
    return pd.DataFrame(rows, columns=["날짜", "공휴일명"])


def test_independent_calendar_accepts_complete_fixture():
    frame = valid_frame()
    assert_holiday_contract(frame, set(frame["날짜"]), [2024])


@pytest.mark.parametrize("mutation", ["wrong_january", "missing_lunar", "invalid_date", "missing_column", "missing_fixed"])
def test_calendar_rejects_known_corruptions(mutation):
    frame = valid_frame()
    if mutation == "wrong_january":
        frame.loc[frame["공휴일명"] == "설날", "날짜"] = "2024-01-21"
    elif mutation == "missing_lunar":
        frame = frame[frame["공휴일명"] != "설날"]
    elif mutation == "invalid_date":
        frame.loc[frame["공휴일명"] == "설날", "날짜"] = "bad-date"
    elif mutation == "missing_fixed":
        frame = frame[frame["공휴일명"] != "현충일"]
    else:
        frame = frame.drop(columns="공휴일명")
    with pytest.raises(AssertionError):
        assert_holiday_contract(frame, set(frame["날짜"]), [2024])


def test_every_listed_holiday_is_sun_holiday(holidays):
    for value in holidays:
        assert day_type_for_date(dt.datetime.strptime(value, "%Y-%m-%d"), holidays) == "sun_holiday"


def test_month_counts_partition_calendar_days(holidays):
    for year in EXPECTED_YEARS:
        for month in range(1, 13):
            counts = build_month_counts(f"{year}{month:02d}", holidays)
            assert set(counts) == set(DAY_TYPES)
            assert sum(counts.values()) == calendar.monthrange(year, month)[1]
