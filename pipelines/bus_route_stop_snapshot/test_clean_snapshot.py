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


def _xlsx(path, rows):
    """A minimal workbook: inline strings, like the portal's files in shape."""
    import zipfile
    from xml.sax.saxutils import escape

    def cell(ref, v):
        if isinstance(v, (int, float)):
            return f'<c r="{ref}"><v>{v}</v></c>'
        return f'<c r="{ref}" t="inlineStr"><is><t>{escape(v)}</t></is></c>'

    body = "".join(
        "<row>" + "".join(cell(f"{chr(65 + j)}{i + 1}", v) for j, v in enumerate(r)) + "</row>"
        for i, r in enumerate(rows))
    sheet = ('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
             f"<sheetData>{body}</sheetData></worksheet>")
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("xl/worksheets/sheet1.xml", sheet)


def test_xlsx_from_the_portal(tmp_path):
    from pipelines.bus_route_stop_snapshot.clean_snapshot import XLSX_COLUMNS, xlsx_records

    f = tmp_path / "서울시버스노선별정류소정보(20261102).xlsx"
    _xlsx(f, [XLSX_COLUMNS,
              [100100223, "3411", 1, 124000344, "25341", "강동공영차고지", 127.174627, 37.564251],
              [107000005, "9409", 8, 206000465, "7832", "판교, 해링턴", 127.0711552409, 37.3673153299]])
    rows = xlsx_records(f)
    assert rows[0]["stdr_de"] == "2026-11-02"
    assert rows[0]["route_id"] == "100100223" and rows[0]["seq"] == 1 and rows[0]["ars"] == "25341"
    assert rows[1]["ars"] == "07832" and rows[1]["node_nm"] == "판교, 해링턴"


def test_xlsx_needs_a_date_in_the_name(tmp_path):
    from pipelines.bus_route_stop_snapshot.clean_snapshot import XLSX_COLUMNS, xlsx_records

    f = tmp_path / "노선정보.xlsx"
    _xlsx(f, [XLSX_COLUMNS])
    with pytest.raises(ValueError):
        xlsx_records(f)
