import pytest

from pipelines.bus_route_path import load_bus_route_path as m


def xml(items, header="0"):
    body = "".join("<itemList>" + "".join(f"<{k}>{v}</{k}>" for k, v in it.items()) + "</itemList>"
                   for it in items)
    return (f"<ServiceResult><msgHeader><headerCd>{header}</headerCd><headerMsg>m</headerMsg>"
            f"</msgHeader><msgBody>{body}</msgBody></ServiceResult>").encode()


def test_parse_items_ok_and_empty():
    assert m.parse_items(xml([{"a": "1"}, {"a": "2"}])) == [{"a": "1"}, {"a": "2"}]
    assert m.parse_items(xml([], header="4")) == []


def test_parse_items_errors():
    with pytest.raises(m.ApiError):
        m.parse_items(xml([], header="8"))
    quota = (b"<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"
             b"</returnAuthMsg><returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>")
    with pytest.raises(m.QuotaExceeded):
        m.parse_items(quota)
    with pytest.raises(m.ApiError):
        m.parse_items(b'<!DOCTYPE x [<!ENTITY a "b">]><ServiceResult/>')


def test_pad_ars():
    assert m.pad_ars("1001") == "01001"
    assert m.pad_ars("0") is None
    assert m.pad_ars("") is None
    assert m.pad_ars(None) is None


def test_match_stations_keeps_order_on_a_road_used_both_ways():
    # out along y=0 to x=4, back along y=0.0001 (same road, other side)
    out = [(x * 0.001, 0.0) for x in range(5)]
    back = [(x * 0.001, 0.0001) for x in range(4, -1, -1)]
    path = out + back
    # station near x=1 outbound, x=3 outbound, x=3 return, x=1 return
    stations = [(0.001, 0.0), (0.003, 0.0), (0.003, 0.0001), (0.001, 0.0001)]
    idx = m.match_stations(stations, path)
    assert idx == sorted(idx)
    assert idx == [1, 3, 6, 8]


def test_match_stations_prefers_order_over_a_closer_earlier_vertex():
    path = [(0.0, 0.0), (0.001, 0.0), (0.002, 0.0)]
    # second station is nearest to vertex 0, but must not go back
    idx = m.match_stations([(0.001, 0.0), (0.0, 0.0)], path)
    assert idx[1] >= idx[0]


def test_build_route_segments_and_turnaround():
    route = {"busRouteId": "100", "routeType": "3", "stStationNm": "A", "edStationNm": "C"}
    stations = [
        {"seq": "1", "arsId": "1001", "station": "s1", "stationNm": "A", "gpsX": "127.000", "gpsY": "37.500", "transYn": "N"},
        {"seq": "2", "arsId": "1002", "station": "s2", "stationNm": "B", "gpsX": "127.002", "gpsY": "37.500", "transYn": "Y"},
        {"seq": "3", "arsId": "0", "station": "s3", "stationNm": "C", "gpsX": "127.004", "gpsY": "37.500", "transYn": "N"},
    ]
    path = [{"no": str(k + 1), "gpsX": f"{127 + k * 0.001:.3f}", "gpsY": "37.500"} for k in range(5)]
    r = m.build_route("777", route, stations, path)
    assert r.status == "ok"
    assert r.route_type == 3
    assert r.turnaround_seq == 2
    assert [s.path_idx for s in r.stations] == [0, 2, 4]
    assert [(g.seq, g.from_ars, g.to_ars, len(g.points)) for g in r.segments] == [
        (1, "01001", "01002", 3), (2, "01002", None, 3)]
    assert 170 < r.segments[0].length_m < 180   # 0.002 deg of longitude at 37.5N ~ 176 m
    assert m.wkt_linestring(r.segments[0].points).startswith("LINESTRING(127.0000000 37.5000000")


def test_build_route_too_short_is_an_error():
    r = m.build_route("1", {}, [], [])
    assert r.status == "error"


class FakeApi(m.BusRouteApi):
    def __init__(self, max_calls, quota_after=None):
        super().__init__("k", session=object(), max_calls=max_calls, pause=0)
        self.quota_after = quota_after

    def _call(self, operation, param, value):
        if self.calls >= self.max_calls:
            raise m.QuotaExceeded("budget")
        self.calls += 1
        if self.quota_after is not None and self.calls > self.quota_after:
            raise m.QuotaExceeded("22")
        if operation == "getBusRouteList":
            return [{"busRouteNm": value, "busRouteId": "id" + value}, {"busRouteNm": value + "0", "busRouteId": "x"}]
        if operation == "getStaionByRoute":
            return [{"seq": "1", "arsId": "1", "gpsX": "127.0", "gpsY": "37.5"},
                    {"seq": "2", "arsId": "2", "gpsX": "127.001", "gpsY": "37.5"}]
        return [{"no": "1", "gpsX": "127.0", "gpsY": "37.5"}, {"no": "2", "gpsX": "127.001", "gpsY": "37.5"}]


def test_run_stops_at_budget_and_reports_left():
    saved = []
    counts = m.run(FakeApi(max_calls=7), ["1", "2", "3"], lambda n, rs: saved.append(n), log=lambda s: None)
    assert saved == ["1", "2"]
    assert counts["ok"] == 2 and counts["left"] == 1


def test_run_stops_on_quota_without_saving_the_route():
    saved = []
    counts = m.run(FakeApi(max_calls=100, quota_after=4), ["1", "2"], lambda n, rs: saved.append(n),
                   log=lambda s: None)
    assert saved == ["1"]
    assert counts["left"] == 1


def test_exact_name_match_only():
    [r] = m.fetch_route(FakeApi(max_calls=10), "302")
    assert r.route_id == "id302"


class AliasApi(FakeApi):
    def _call(self, operation, param, value):
        self.calls += 1
        assert operation != "getBusRouteList"   # ids are known
        if operation == "getStaionByRoute":
            return [{"seq": "1", "arsId": value[-3:], "gpsX": "127.0", "gpsY": "37.5"},
                    {"seq": "2", "arsId": "9" + value[-3:], "gpsX": "127.001", "gpsY": "37.5"}]
        return [{"no": "1", "gpsX": "127.0", "gpsY": "37.5"}, {"no": "2", "gpsX": "127.001", "gpsY": "37.5"}]


def test_alias_stores_every_variant_under_the_topis_name():
    api = AliasApi(max_calls=10)
    rs = m.fetch_route(api, "한강버스01")
    assert [r.route_nm for r in rs] == ["한강버스01|한강버스(서부)", "한강버스01|한강버스(동부옥수)",
                                        "한강버스01|한강버스(동부압구정)"]
    assert {r.od_route_nm for r in rs} == {"한강버스01"}
    assert [r.route_id for r in rs] == ["123000023", "123000024", "123000021"]
    assert all(r.status == "ok" for r in rs)
    assert api.calls == 6 == m.calls_needed("한강버스01")


def test_ordinary_route_keeps_its_own_name():
    rs = m.fetch_route(FakeApi(max_calls=10), "302")
    assert len(rs) == 1 and rs[0].od_route_nm == "302"


class BrokenApi(FakeApi):
    def _call(self, operation, param, value):
        self.calls += 1
        raise m.ApiError("headerCd 7")


def test_run_stops_after_errors_in_a_row():
    saved = []
    counts = m.run(BrokenApi(max_calls=100), [str(i) for i in range(10)], lambda n, rs: saved.append(n),
                   log=lambda s: None)
    assert len(saved) == 2            # two recorded, the third ends the run unrecorded
    assert counts["left"] == 8


class LetteredApi(FakeApi):
    def _call(self, operation, param, value):
        self.calls += 1
        if operation == "getBusRouteList":
            return [{"busRouteNm": "2312A", "busRouteId": "A"}, {"busRouteNm": "2312B", "busRouteId": "B"},
                    {"busRouteNm": "2312A", "busRouteId": "A2"},
                    {"busRouteNm": "23120", "busRouteId": "no"}, {"busRouteNm": "N2312", "busRouteId": "no"}]
        if operation == "getStaionByRoute":
            return [{"seq": "1", "arsId": "1", "gpsX": "127.0", "gpsY": "37.5"},
                    {"seq": "2", "arsId": "2", "gpsX": "127.001", "gpsY": "37.5"}]
        return [{"no": "1", "gpsX": "127.0", "gpsY": "37.5"}, {"no": "2", "gpsX": "127.001", "gpsY": "37.5"}]


def test_lettered_variants_are_stored_under_the_topis_name():
    api = LetteredApi(max_calls=10)
    rs = m.fetch_route(api, "2312")
    assert [(r.route_nm, r.route_id, r.od_route_nm) for r in rs] == [("2312|2312A", "A", "2312"), ("2312|2312B", "B", "2312")]
    assert api.calls == 5


class FlakyEngine:
    def __init__(self, failures):
        self.failures, self.disposed, self.sql = failures, 0, []

    def begin(self):
        engine = self

        class Ctx:
            def __enter__(self):
                if engine.failures:
                    engine.failures -= 1
                    from sqlalchemy.exc import OperationalError
                    raise OperationalError("x", {}, Exception("server closed the connection"))
                return self

            def __exit__(self, *a):
                return False

            def execute(self, stmt, params=None):
                engine.sql.append(str(stmt).split()[0])

        return Ctx()

    def dispose(self):
        self.disposed += 1


def test_save_retries_a_dropped_connection(monkeypatch):
    pytest.importorskip("sqlalchemy")
    monkeypatch.setattr(m.time, "sleep", lambda s: None)
    engine = FlakyEngine(failures=1)
    m.save_routes(engine, "1", [m.RouteResult("1", "not_found")])
    assert engine.disposed == 1 and engine.sql == ["DELETE", "INSERT"]


class CommuterApi(LetteredApi):
    def _call(self, operation, param, value):
        if operation == "getBusRouteList":
            self.calls += 1
            return [{"busRouteNm": "8762출근", "busRouteId": "M"}, {"busRouteNm": "8762퇴근", "busRouteId": "E"},
                    {"busRouteNm": "87620", "busRouteId": "no"}]
        return super()._call(operation, param, value)


def test_commuter_variants():
    rs = m.fetch_route(CommuterApi(max_calls=10), "8762")
    assert [(r.route_nm, r.route_id) for r in rs] == [("8762|8762출근", "M"), ("8762|8762퇴근", "E")]
