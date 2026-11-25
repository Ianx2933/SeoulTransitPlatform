"""clean_snapshot: comma in stop names, lost leading zeros, no-number stops."""

import pytest

from pipelines.bus_route_stop_snapshot.clean_snapshot import pad_ars, parse_line


def test_plain_line():
    row = parse_line("20240101,100100165,1155,1,222000625,42106,수락산입구.동부거성아파트,127.1125963,37.70799826,")
    assert row["stdr_de"] == "2024-01-01"
    assert row["route_nm"] == "1155" and row["seq"] == 1 and row["ars"] == "42106"
    assert row["lng"] == pytest.approx(127.1125963)


def test_comma_in_name_without_trailing_comma():
    row = parse_line("20240101,100100086,601,8,115000635,16016,공항중학교.마곡엠밸리9,10단지,126.815552,37.5608")
    assert row["node_nm"] == "공항중학교.마곡엠밸리9,10단지"
    assert row["lat"] == pytest.approx(37.5608)


def test_stop_numbers():
    assert pad_ars("3689") == "03689"
    assert pad_ars("0") is None
    assert pad_ars("42106") == "42106"


def test_bad_position_is_rejected():
    with pytest.raises(ValueError):
        parse_line("20240101,1,1,1,1,1,x,0,0,")
