"""Result-code handling in the hourly loader.

Seoul Open API error responses (invalid key, no data) put RESULT at the
document root instead of inside the service element. The loader used to look
only for a RESULT child, so an invalid key loaded zero rows and exited 0.
"""
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from pipelines.hourly_od_estimation import load_hourly_boarding as loader


def response(xml: str):
    return SimpleNamespace(content=xml.encode("utf-8"), raise_for_status=lambda: None)


INVALID_KEY = """<?xml version="1.0" encoding="UTF-8"?>
<RESULT><CODE>INFO-100</CODE><MESSAGE>인증키가 유효하지 않습니다.</MESSAGE></RESULT>"""

NOT_PUBLISHED = """<?xml version="1.0" encoding="UTF-8"?>
<RESULT><CODE>INFO-200</CODE><MESSAGE>해당하는 데이터가 없습니다.</MESSAGE></RESULT>"""

NO_RESULT = """<?xml version="1.0" encoding="UTF-8"?>
<CardBusTimeNew><list_total_count>0</list_total_count></CardBusTimeNew>"""

SUCCESS = """<?xml version="1.0" encoding="UTF-8"?>
<CardBusTimeNew>
  <list_total_count>1</list_total_count>
  <RESULT><CODE>INFO-000</CODE><MESSAGE>정상 처리되었습니다</MESSAGE></RESULT>
  <row>
    <USE_YM>202608</USE_YM><RTE_NO>100</RTE_NO><RTE_NM>100번(하계동~용산구청)</RTE_NM>
    <STOPS_ID>110000327</STOPS_ID><STOPS_ARS_NO>11428</STOPS_ARS_NO>
    <SBWY_STNS_NM>한성여객종점(00001)</SBWY_STNS_NM><REG_YMD>20260903</REG_YMD>
    <HR_1_GET_ON_NOPE>2.0</HR_1_GET_ON_NOPE><HR_3_GET_ON_TNOPE>44.0</HR_3_GET_ON_TNOPE>
  </row>
</CardBusTimeNew>"""


def fetch(xml):
    with patch.object(loader.requests, "get", return_value=response(xml)):
        return loader.fetch_page("KEY", 1, 1000, "202608")


def test_invalid_key_raises_instead_of_loading_nothing():
    with pytest.raises(loader.SeoulApiResultError, match="INFO-100"):
        fetch(INVALID_KEY)


def test_unpublished_month_is_its_own_error():
    with pytest.raises(loader.MonthNotPublished, match="INFO-200"):
        fetch(NOT_PUBLISHED)


def test_response_without_result_is_an_error():
    with pytest.raises(loader.SeoulApiResultError, match="no RESULT"):
        fetch(NO_RESULT)


def test_success_is_parsed():
    total, records = fetch(SUCCESS)
    assert total == 1
    assert len(records) == 24
    by_hour = {r["hour"]: r["boarding_passengers"] for r in records}
    assert by_hour[1] == 2.0      # HR_1 uses the NOPE spelling in the source
    assert by_hour[3] == 44.0


def run_main(argv, load_month_error):
    with patch("sys.argv", ["load_hourly_boarding.py", *argv]), \
         patch.dict("os.environ", {"PIPELINE_DB_URL": "postgresql://x", "SEOUL_HOURLY_API_KEY": "KEY"}), \
         patch.object(loader, "load_dotenv"), \
         patch.object(loader, "make_engine"), \
         patch.object(loader, "latest_loaded_month", return_value="202609"), \
         patch.object(loader, "load_month", side_effect=load_month_error):
        loader.main()


def test_scheduled_run_skips_unpublished_month():
    run_main([], loader.MonthNotPublished("202610 is not published yet"))


def test_scheduled_run_fails_on_invalid_key():
    with pytest.raises(loader.SeoulApiResultError):
        run_main([], loader.SeoulApiResultError("INFO-100"))


def test_explicit_month_that_is_not_published_fails():
    with pytest.raises(loader.MonthNotPublished):
        run_main(["--use-ym", "202610"], loader.MonthNotPublished("202610 is not published yet"))
