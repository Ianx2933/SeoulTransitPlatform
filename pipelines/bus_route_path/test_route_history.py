"""plan_save: which stored paths are archived when a route is fetched again."""

from pipelines.bus_route_path.load_bus_route_path import RouteResult, Station, plan_save, station_sig


def _route(name, ars_list, status="ok"):
    r = RouteResult(name, status)
    r.stations = [Station(i + 1, a, f"st{i}", f"s{i}", 127.0, 37.5 + i / 1000, False)
                  for i, a in enumerate(ars_list)]
    return r


def test_sig_uses_station_id_without_ars():
    assert station_sig([("01001", "1"), (None, "77")]) == "01001>id:77"


def test_same_order_carries_path_since():
    plan = plan_save({"3411": ("01001>01002", "T0")}, [_route("3411", ["01001", "01002"])])
    assert not plan.keep_stored
    assert plan.archive == []
    assert plan.since == {"3411": "T0"}


def test_changed_order_is_archived():
    plan = plan_save({"3411": ("01001>01002", "T0")}, [_route("3411", ["01001", "01009", "01002"])])
    assert plan.archive == [("3411", "changed")]
    assert plan.since == {}


def test_gone_route_and_variant_are_archived():
    stored = {"2312|2312A": ("01001>01002", "T0"), "2312|2312B": ("01003>01004", "T1")}
    plan = plan_save(stored, [_route("2312|2312A", ["01001", "01002"])])
    assert plan.archive == [("2312|2312B", "gone")]
    assert plan.since == {"2312|2312A": "T0"}

    gone = plan_save({"3411": ("01001>01002", "T0")}, [RouteResult("3411", "not_found")])
    assert gone.archive == [("3411", "gone")]


def test_failed_fetch_keeps_stored_path():
    plan = plan_save({"3411": ("01001>01002", "T0")}, [RouteResult("3411", "error", message="x")])
    assert plan.keep_stored


def test_first_fetch_has_nothing_to_archive():
    plan = plan_save({}, [RouteResult("3411", "error")])
    assert not plan.keep_stored and plan.archive == []
