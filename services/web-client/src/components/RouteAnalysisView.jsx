import { useCallback, useEffect, useMemo, useState } from "react";
import { groupByPlace, modifiers, nextPick, NO_PICK, singleSeq } from "../utils/stopPick.js";
import { CircleMarker, GeoJSON, MapContainer, Pane, Polyline, Popup, TileLayer, Tooltip, useMap } from "react-leaflet";

import { fetchOdRouteNames, fetchRouteDongOd, fetchRoutePath, fetchRouteStopPoints, fetchStopPath } from "../api/routeAnalysisApi.js";
import { isPastRoute, pastPoints, pastSegments, periodEndDay, stopMatch, stopPathQuery } from "../utils/pastRoute.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { shade, shadeValues, topRows } from "../utils/dongFlows.js";
import { busRouteStyle } from "../utils/busRouteType.js";
import {
  boundsOf,
  roadBetween,
  pathStopPoints,
  placeStops,
  seqOfArs,
  splitAtTurnaround
} from "../utils/routeGeometry.js";
import { findTurnaround } from "../utils/routeLoadChart.js";
import { useRoadLoad } from "../hooks/useRoadLoad.js";
import { useStoredNumber } from "../hooks/useStoredNumber.js";
import { useMediaQuery } from "../hooks/useMediaQuery.js";
import useLatest from "../hooks/useLatest.js";
import { useDistrictBoundaries } from "../hooks/useDistrictBoundaries.js";

/** Route lines: thick enough to read over the map and district outlines. */
const ROUTE_WEIGHT = 7;
import { DEFAULT_MIN_LOAD, styleSegments } from "../utils/routeLoad.js";
import { basisKey, fixedScaleMax, TYPICAL } from "../utils/basis.js";
import RoadExploreCard from "./RoadExploreCard.jsx";
import RoadPointsLayer from "./RoadPointsLayer.jsx";
import RouteAnalysisPanel from "./RouteAnalysisPanel.jsx";
import RouteLoadLayer from "./RouteLoadLayer.jsx";
import RouteRoadCard, { MAX_COMBINED_ROUTES } from "./RouteRoadCard.jsx";

const SEOUL_CENTER = [37.5665, 126.978];
const COLORS = {
  up: "#3d5bab",
  down: "#7ac143",
  selected: "#f99d1c",
  segment: "#fcd9a0",
  ink: "#1f2328"
};

/**
 * Route analysis page: the map fills the page and the analysis sits in a
 * panel on the right (below the map on narrow screens).
 *
 * When a route is analysed, its stop coordinates are loaded from the map
 * demand API and matched to the analysis stops (by ARS, then by name). The
 * map draws the route in the chart's colours (upbound blue, downbound green),
 * the chosen stop in yellow, the stretch its riders stay on past the
 * threshold, and the chosen section. Clicking a stop on the map selects it
 * in the panel.
 */
export default function RouteAnalysisView({ busLines }) {
  const { t, lang } = useI18n();
  const fmtRiders = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 0 });
  const translate = useLatest(t);
  const [routeData, setRouteData] = useState(null);
  const [turnaround, setTurnaround] = useState(null);
  // Picked stops: a click picks one, Ctrl adds or removes one, Shift a range (stopPick.js).
  const [pick, setPick] = useState(NO_PICK);
  const stopSeq = singleSeq(pick);
  const setStopSeq = useCallback((seq) => setPick(seq == null ? NO_PICK : { anchor: seq, seqs: [seq] }), []);
  const [reachSeq, setReachSeq] = useState(null);
  const [segment, setSegment] = useState(null);
  const [points, setPoints] = useState([]);
  const [pointsState, setPointsState] = useState("idle");
  const [query, setQuery] = useState({ dayType: "weekday", basis: TYPICAL });
  const queryBasisKey = basisKey(query.basis);
  // The district effect runs when the basis key changes and reads the basis here.
  const queryBasis = useLatest(query.basis);
  const [roadPath, setRoadPath] = useState(null);
  const [dongOn, setDongOn] = useState(false);
  const [boundaryOn, setBoundaryOn] = useState(false);
  const boundaries = useDistrictBoundaries(boundaryOn);
  const [dongData, setDongData] = useState(null);
  const [dongState, setDongState] = useState("idle");
  const [dongSelected, setDongSelected] = useState(null);
  const [dongDirection, setDongDirection] = useState("from");

  const routeName = routeData?.노선명 ?? null;

  // Route search offers the representative OD's own names; the map's list
  // (boarding data names) is the fallback if they cannot be loaded.
  const [odRouteNames, setOdRouteNames] = useState(null);
  useEffect(() => {
    let ignore = false;
    fetchOdRouteNames()
      .then((names) => {
        if (!ignore && Array.isArray(names) && names.length > 0) setOdRouteNames(names);
      })
      .catch(() => {});
    return () => {
      ignore = true;
    };
  }, []);

  // Side panels fold away to give the map the screen; remembered per browser.
  const [leftOpen, setLeftOpen] = useStoredNumber("routeLeftPanelOpen", 1);
  const [rightOpen, setRightOpen] = useStoredNumber("routeRightPanelOpen", 1);
  // Phones: the map fills the screen, the map cards open from a button, and the
  // analysis is a bottom sheet showing only the route search until pulled up.
  const narrow = useMediaQuery("(max-width: 900px)");
  const [cardsOpen, setCardsOpen] = useState(false);
  const [sheetUp, setSheetUp] = useState(false);
  const [touchY, setTouchY] = useState(null);

  // Demand colours: the route (plus added routes) by riders on board.
  const [roadOn, setRoadOn] = useState(false);
  const [extraRoutes, setExtraRoutes] = useState([]);
  const [roadMinLoad, setRoadMinLoad] = useStoredNumber("roadMinLoad", DEFAULT_MIN_LOAD);
  const [roadPicked, setRoadPicked] = useState([]);
  const [explore, setExplore] = useState(null);
  useEffect(() => {
    setExtraRoutes([]);
    setRoadPicked([]);
    setExplore(null);
  }, [routeName]);
  const roadRequest =
    roadOn && routeName
      ? { kind: "routes", routes: [routeName, ...extraRoutes], dayType: query.dayType, basis: query.basis }
      : null;
  const roadLoad = useRoadLoad(roadRequest);
  // A look-up follows the panel's day type, months and hours.
  const exploreLoad = useRoadLoad(
    roadOn && explore ? { ...explore, dayType: query.dayType, basis: query.basis } : null
  );
  const roadStyled = useMemo(() => {
    if (exploreLoad.result) {
      return styleSegments(exploreLoad.result.segments, {
        minLoad: roadMinLoad,
        fixedMax: fixedScaleMax(exploreLoad.result, query.basis)
      });
    }
    if (!roadLoad.result) return null;
    const known = new Set((roadLoad.result.routes || []));
    return styleSegments(roadLoad.result.segments, {
      minLoad: roadMinLoad,
      routes: roadPicked.filter((r) => known.has(r)),
      fixedMax: fixedScaleMax(roadLoad.result, query.basis)
    });
  }, [roadLoad.result, exploreLoad.result, roadMinLoad, roadPicked, query.basis]);

  useEffect(() => {
    if (!routeName) {
      setPoints([]);
      setPointsState("idle");
      return undefined;
    }
    let ignore = false;
    setPointsState("loading");
    fetchRouteStopPoints({ route: routeName })
      .then((data) => {
        if (ignore) return;
        setPoints(Array.isArray(data) ? data : []);
        setPointsState("done");
      })
      .catch(() => {
        if (ignore) return;
        setPoints([]);
        setPointsState("error");
      });
    return () => {
      ignore = true;
    };
  }, [routeName]);

  // Road path and turnaround from the city's bus route API (through our API).
  useEffect(() => {
    setRoadPath(null);
    if (!routeName) return undefined;
    let ignore = false;
    fetchRoutePath({ route: routeName })
      .then((data) => {
        if (!ignore) setRoadPath(data);
      })
      .catch(() => {
        if (!ignore) setRoadPath({ path: [], turnaroundArs: null, missing: true });
      });
    return () => {
      ignore = true;
    };
  }, [routeName]);

  const allSeqs = useMemo(() => (routeData?.stops || []).map((s) => s.seq).sort((a, b) => a - b), [routeData]);
  const pickStop = useCallback((seq, mods) => setPick((p) => nextPick(p, seq, mods, allSeqs)), [allSeqs]);
  const pickedSet = useMemo(() => new Set(pick.seqs), [pick]);
  const turnaroundFromBis = useMemo(() => {
    if (!roadPath?.turnaroundArs || !routeData) return null;
    return seqOfArs(routeData.stops, roadPath.turnaroundArs, findTurnaround(routeData.stops));
  }, [roadPath, routeData]);
  const hasRoad = Array.isArray(roadPath?.path) && roadPath.path.length > 1;

  // A route that ran differently in this period: redraw it from the period's stop order.
  const match = useMemo(() => (routeData && roadPath ? stopMatch(routeData.stops, roadPath) : null), [routeData, roadPath]);
  // No stored path today (route withdrawn or renamed, 1155): draw it from the stops too.
  const noCurrent = Boolean(roadPath?.missing) && (routeData?.stops?.length || 0) >= 2;
  const past = isPastRoute(match) || noCurrent;
  const pastQuery = past ? stopPathQuery(routeData.stops) : null;
  const pastAt = periodEndDay(query.basis?.period);
  const [pastPath, setPastPath] = useState(null);
  useEffect(() => {
    setPastPath(null);
    if (!pastQuery) return undefined;
    let ignore = false;
    fetchStopPath({ stops: pastQuery, route: routeName, at: pastAt })
      .then((data) => {
        if (!ignore) setPastPath(data);
      })
      .catch(() => {
        if (!ignore) setPastPath(null);
      });
    return () => {
      ignore = true;
    };
  }, [pastQuery, routeName, pastAt]);
  const pastLines = useMemo(
    () => (pastPath && routeData ? pastSegments(routeData.stops, pastPath) : []),
    [pastPath, routeData]
  );
  const showPast = past && pastLines.length > 0;
  const roadColor = routeName ? busRouteStyle(routeName).bg : COLORS.up;

  // District OD, loaded only while the district view is on.
  useEffect(() => {
    if (!dongOn || !routeName) {
      setDongData(null);
      setDongState("idle");
      return undefined;
    }
    let ignore = false;
    setDongState("loading");
    const tr = translate.current;
    fetchRouteDongOd({
      route: routeName,
      dayType: query.dayType,
      basis: queryBasis.current,
      messages: { defaultMessage: tr("route.dong.error"), notFoundMessage: tr("route.dong.error") }
    })
      .then((data) => {
        if (ignore) return;
        setDongData(data);
        setDongState("done");
        setDongSelected((current) => (data.districts.some((d) => d.code === current) ? current : null));
      })
      .catch(() => {
        if (ignore) return;
        setDongData(null);
        setDongState("error");
      });
    return () => {
      ignore = true;
    };
  }, [translate, queryBasis, dongOn, routeName, query.dayType, queryBasisKey]);

  const dongFeatures = useMemo(
    () =>
      (dongData?.districts || [])
        .filter((d) => d.geometry)
        .map((d) => ({
          type: "Feature",
          properties: { code: d.code, name: d.name },
          geometry: JSON.parse(d.geometry)
        })),
    [dongData]
  );
  const dongValues = useMemo(
    () => shadeValues(dongData, dongSelected, dongDirection),
    [dongData, dongSelected, dongDirection]
  );
  const dongMax = Math.max(0, ...dongValues.values());

  const variantPaths = (roadPath?.variants || []).filter((v) => Array.isArray(v.path) && v.path.length > 1);
  // Demand-layer positions first; the stored route path fills the rest (piers, new stops).
  const placed = useMemo(
    // The route's own positions first: an ARS shared by a Gyeonggi and a Seoul stop must not pull the line to the wrong one.
    () => placeStops(routeData?.stops || [], [...pathStopPoints(roadPath), ...pastPoints(pastPath), ...points]),
    [routeData, points, roadPath, pastPath]
  );
  const effectiveTurnaround = turnaroundFromBis ?? turnaround;
  const lines = useMemo(() => splitAtTurnaround(placed, effectiveTurnaround), [placed, effectiveTurnaround]);
  // Reach and segment lines follow the road the route takes (each stored run of a
  // route kept as several), not straight lines between stops.
  const roadPaths = useMemo(() => {
    if (!hasRoad || showPast) return [];
    return variantPaths.length > 0 ? variantPaths.map((v) => v.path) : [roadPath.path];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasRoad, showPast, roadPath]);
  const reachPath = useMemo(
    () => roadBetween(placed, roadPaths, stopSeq, reachSeq),
    [placed, roadPaths, stopSeq, reachSeq]
  );
  const segmentPath = useMemo(
    () => (segment ? roadBetween(placed, roadPaths, segment[0], segment[1]) : []),
    [placed, roadPaths, segment]
  );
  const bounds = useMemo(() => {
    if (showPast) return boundsOf(pastLines.flatMap((s) => s.path.map(([lat, lng]) => ({ lat, lng }))));
    if (hasRoad) return boundsOf(roadPath.path.map(([lat, lng]) => ({ lat, lng })));
    return boundsOf(placed);
  }, [placed, hasRoad, roadPath, showPast, pastLines]);
  const selected = placed.find((p) => p.seq === stopSeq) || null;

  let note = t("route.map.empty");
  if (routeName && pointsState === "loading") note = t("route.map.loading");
  else if (routeName && pointsState === "error") note = t("route.map.error");
  else if (routeName && pointsState === "done") {
    note =
      placed.length === 0
        ? t("route.map.none")
        : t("route.map.placed", { placed: placed.length, all: routeData.stops.length });
    if (showPast) {
      note += " " + (noCurrent || !match ? t("route.map.noCurrent") : t("route.map.past", { value: Math.round(match.ratio * 100) }));
      if (pastPath?.snapshotDate) note += " " + t("route.map.pastMaster", { date: pastPath.snapshotDate });
      if (pastPath?.missingStops > 0) note += " " + t("route.map.pastUnplaced", { value: pastPath.missingStops });
      if (pastLines.some((s) => s.straight)) note += " " + t("route.map.pastStraight");
      if (roadOn) note += " " + t("route.map.pastRoad");
    } else if (roadPath) note += " " + (hasRoad ? t("route.map.road") : t("route.map.straight"));
  }

  return (
    <div
      className={[
        "route-view",
        leftOpen ? "" : "route-view--left-closed",
        rightOpen ? "" : "route-view--right-closed",
        cardsOpen ? "route-view--cards-open" : ""
      ].join(" ")}
    >
      <div className="route-view__map">
        <MapContainer center={SEOUL_CENTER} zoom={12} className="route-view__leaflet" scrollWheelZoom>
          <TileLayer
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          />
          <FitRoute bounds={bounds} routeName={routeName} rightOpen={Boolean(rightOpen)} narrow={narrow} />

          {boundaryOn && boundaries && (
            <Pane name="district-outlines" style={{ zIndex: 340, pointerEvents: "none" }}>
              <GeoJSON
                data={boundaries}
                interactive={false}
                style={{ color: "#2b2f36", weight: 1.4, opacity: 0.7, fill: false }}
              />
            </Pane>
          )}
          <Pane name="districts" style={{ zIndex: 350 }}>
            {/* The road view draws riders itself; district fills would hide it. */}
            {dongOn && !roadOn && dongFeatures.length > 0 && (
              <GeoJSON
                key={`${dongData.노선명}-${dongData.기준일자}-${queryBasisKey}-${dongSelected}-${dongDirection}`}
                data={{ type: "FeatureCollection", features: dongFeatures }}
                style={(feature) => {
                  const code = feature.properties.code;
                  const chosen = code === dongSelected;
                  return {
                    color: chosen ? COLORS.ink : "#5b6470",
                    weight: chosen ? 3 : 1,
                    fillColor: chosen ? COLORS.selected : COLORS.up,
                    fillOpacity: chosen ? 0.55 : shade(dongValues.get(code), dongMax)
                  };
                }}
                onEachFeature={(feature, layer) => {
                  layer.bindTooltip(feature.properties.name, { sticky: true });
                  layer.on("click", () =>
                    setDongSelected((current) => (current === feature.properties.code ? null : feature.properties.code))
                  );
                }}
              />
            )}
          </Pane>

          {segmentPath.length > 1 && (
            <Polyline positions={segmentPath} interactive={false} pathOptions={{ color: COLORS.segment, weight: 14, opacity: 0.9 }} />
          )}
          {/* The period's route, redrawn from its stops; the current path faint underneath. */}
          {showPast && hasRoad && (
            <Polyline positions={roadPath.path} pathOptions={{ color: "#8a929c", weight: 3, opacity: 0.55, dashArray: "2 8" }}>
              <Tooltip sticky>{t("route.map.currentPath")}</Tooltip>
            </Polyline>
          )}
          {showPast &&
            pastLines.map((s) => (
              <Polyline
                key={s.key}
                positions={s.path}
                pathOptions={{ color: s.color, weight: Math.max(ROUTE_WEIGHT - 2, s.weight), opacity: 0.95, dashArray: s.straight ? "8 8" : null }}
              >
                <Tooltip sticky>{t("route.map.pastSegment", { value: fmtRiders(s.load) })}</Tooltip>
              </Polyline>
            ))}
          {!roadOn && !showPast && hasRoad && variantPaths.length === 0 && (
            <Polyline positions={roadPath.path} pathOptions={{ color: roadColor, weight: ROUTE_WEIGHT, opacity: 0.9 }} />
          )}
          {/* A route stored as several runs (한강버스01, 8762 출근/퇴근): draw each. */}
          {!roadOn &&
            !showPast &&
            variantPaths.map((v) => (
              <Polyline key={v.name} positions={v.path} pathOptions={{ color: roadColor, weight: ROUTE_WEIGHT, opacity: 0.9 }}>
                <Tooltip sticky>{v.name}</Tooltip>
              </Polyline>
            ))}
          {!roadOn && !showPast && !hasRoad && lines.up.length > 1 && (
            <Polyline positions={lines.up} pathOptions={{ color: COLORS.up, weight: ROUTE_WEIGHT, opacity: 0.9 }} />
          )}
          {!roadOn && !showPast && !hasRoad && lines.down.length > 1 && (
            <Polyline positions={lines.down} pathOptions={{ color: COLORS.down, weight: ROUTE_WEIGHT, opacity: 0.95 }} />
          )}
          {roadOn && !showPast && roadStyled && (
            <RouteLoadLayer
              result={exploreLoad.result || roadLoad.result}
              segments={roadStyled.segments}
              onSegmentClick={(s) => setExplore({ kind: "segment", from: s.fromArs, to: s.toArs })}
            />
          )}
          {roadOn && exploreLoad.result && <RoadPointsLayer result={exploreLoad.result} />}
          {reachPath.length > 1 && (
            <Polyline
              positions={reachPath}
              interactive={false}
              pathOptions={{ color: COLORS.selected, weight: ROUTE_WEIGHT + 4, opacity: 0.95 }}
            />
          )}

          {/* Stops in their own pane above every line: a line drawn later (the reach of a
              stop, a section) would otherwise cover them and take their clicks. */}
          <Pane name="route-stops" style={{ zIndex: 450 }}>
          {groupByPlace(placed).map((group) => {
            const p = group[0];
            const isDown = (q) => effectiveTurnaround != null && q.seq > effectiveTurnaround;
            const picked = group.some((q) => pickedSet.has(q.seq)) && pick.seqs.length > 1;
            if (group.length === 1) {
              return (
                <CircleMarker
                  key={p.seq}
                  center={[p.lat, p.lng]}
                  radius={picked ? 6 : 4}
                  pathOptions={{
                    color: picked ? COLORS.ink : isDown(p) ? COLORS.down : COLORS.up,
                    weight: 2,
                    fillColor: picked ? COLORS.selected : "#ffffff",
                    fillOpacity: 1
                  }}
                  eventHandlers={{ click: (e) => pickStop(p.seq, modifiers(e)) }}
                >
                  <Tooltip direction="top" offset={[0, -4]}>
                    {p.seq} {p.name}
                  </Tooltip>
                </CircleMarker>
              );
            }
            // One place, two or more stops (a terminus at both ends): choose which.
            return (
              <CircleMarker
                key={group.map((q) => q.seq).join("-")}
                center={[p.lat, p.lng]}
                radius={picked ? 7 : 6}
                pathOptions={{ color: COLORS.ink, weight: 2, fillColor: picked ? COLORS.selected : "#ffffff", fillOpacity: 1 }}
              >
                <Tooltip direction="top" offset={[0, -6]}>
                  {group.map((q) => `${q.seq} ${q.name}`).join(" · ")}
                </Tooltip>
                <Popup>
                  <div className="stop-choice">
                    {group.map((q) => (
                      <button
                        key={q.seq}
                        type="button"
                        className="chip"
                        onClick={(e) => pickStop(q.seq, modifiers(e))}
                      >
                        {q.seq} {q.name} · {isDown(q) ? t("route.pick.down") : t("route.pick.up")}
                      </button>
                    ))}
                  </div>
                </Popup>
              </CircleMarker>
            );
          })}
          </Pane>

          <Pane name="route-stop-selected" style={{ zIndex: 460 }}>
          {selected && (
            <CircleMarker
              center={[selected.lat, selected.lng]}
              radius={10}
              pathOptions={{ color: COLORS.ink, weight: 3, fillColor: COLORS.selected, fillOpacity: 1 }}
            >
              <Tooltip direction="top" offset={[0, -10]} permanent>
                {selected.seq} {selected.name}
              </Tooltip>
            </CircleMarker>
          )}
          </Pane>
        </MapContainer>
        <p className="map-note" role="status">{note}</p>
        {routeName && narrow && (
          <button
            type="button"
            className="cards-toggle"
            aria-expanded={cardsOpen}
            onClick={() => setCardsOpen((open) => !open)}
          >
            {cardsOpen ? t("route.panels.cardsHide") : t("route.panels.cardsShow")}
          </button>
        )}
        {routeName && !leftOpen && (
          <button type="button" className="side-tab side-tab--left" onClick={() => setLeftOpen(1)}>
            {t("route.panels.showLeft")} ›
          </button>
        )}
        {routeName && (
          <div className="route-cards">
          <button
            type="button"
            className="side-fold side-fold--left"
            onClick={() => setLeftOpen(0)}
            aria-label={t("route.panels.hideLeft")}
            title={t("route.panels.hideLeft")}
          >
            ‹ {t("route.panels.hide")}
          </button>
          <RouteRoadCard
            key={routeName}
            routeName={routeName}
            on={roadOn}
            setOn={setRoadOn}
            extraRoutes={extraRoutes}
            setExtraRoutes={setExtraRoutes}
            state={roadLoad.state}
            error={roadLoad.error}
            result={roadLoad.result}
            styled={exploreLoad.result ? null : roadStyled}
            minLoad={roadMinLoad}
            setMinLoad={setRoadMinLoad}
            picked={roadPicked}
            setPicked={setRoadPicked}
            stop={routeData?.stops?.find((s) => s.seq === stopSeq) || null}
            basis={query.basis}
            onStopLookup={(direction) => {
              const stop = routeData?.stops?.find((s) => s.seq === stopSeq);
              // The route goes along: its stop's number can also name a Seoul stop elsewhere.
              if (stop?.ars) setExplore({ kind: "stop", ars: String(stop.ars), direction, route: routeName });
            }}
          />
          {roadOn && (
            <RoadExploreCard
              request={explore}
              state={exploreLoad.state}
              error={exploreLoad.error}
              result={exploreLoad.result}
              hours={query.basis.hours}
              onClose={() => setExplore(null)}
              onCombineRoutes={(routes) => {
                setExtraRoutes(routes.filter((r) => r !== routeName).slice(0, MAX_COMBINED_ROUTES - 1));
                setRoadPicked([]);
                setExplore(null);
              }}
            />
          )}
          <label className="check side-card">
            <input type="checkbox" checked={boundaryOn} onChange={(e) => setBoundaryOn(e.target.checked)} />
            {t("route.showBoundaries")}
          </label>
          <DongCard
            on={dongOn}
            setOn={setDongOn}
            state={dongState}
            data={dongData}
            selected={dongSelected}
            setSelected={setDongSelected}
            direction={dongDirection}
            setDirection={setDongDirection}
            values={dongValues}
          />
          </div>
        )}
      </div>
      {rightOpen ? (
        <button
          type="button"
          className="side-fold side-fold--right"
          onClick={() => setRightOpen(0)}
          aria-label={t("route.panels.hideRight")}
          title={t("route.panels.hideRight")}
        >
          ›
        </button>
      ) : (
        <button type="button" className="side-tab side-tab--right" onClick={() => setRightOpen(1)}>
          ‹ {routeName ? t("route.panels.showRightRoute", { route: routeName }) : t("route.panels.showRight")}
        </button>
      )}
      <div className={sheetUp ? "route-sheet route-sheet--up" : "route-sheet"}>
      {narrow && (
        <button
          type="button"
          className="route-sheet__handle"
          aria-expanded={sheetUp}
          aria-label={sheetUp ? t("route.panels.sheetDown") : t("route.panels.sheetUp")}
          onClick={() => setSheetUp((up) => !up)}
          onTouchStart={(e) => setTouchY(e.touches[0].clientY)}
          onTouchEnd={(e) => {
            if (touchY === null) return;
            const dy = e.changedTouches[0].clientY - touchY;
            setTouchY(null);
            if (dy < -30) setSheetUp(true);
            else if (dy > 30) setSheetUp(false);
          }}
        >
          <span className="route-sheet__grip" />
        </button>
      )}
      <RouteAnalysisPanel
        busLines={odRouteNames || busLines}
        stopSeq={stopSeq}
        onStopSeqChange={setStopSeq}
        pickedSeqs={pick.seqs}
        onPick={pickStop}
        onRouteData={setRouteData}
        onTurnaround={setTurnaround}
        onReach={setReachSeq}
        onSegment={setSegment}
        onQuery={setQuery}
        turnaroundFromBis={turnaroundFromBis}
      />
      </div>
    </div>
  );
}

/**
 * District OD card on the map: turns the district layer on, and lists where
 * the riders of the chosen district go (or come from) on this route.
 */
function DongCard({ on, setOn, state, data, selected, setSelected, direction, setDirection, values }) {
  const { t, lang } = useI18n();
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const pct = (v) => `${(v * 100).toFixed(1)}%`;
  const chosen = data?.districts.find((d) => d.code === selected) || null;
  const rows = topRows(values, data?.districts);

  return (
    <section className="dong-card" aria-labelledby="dong-title">
      <div className="dong-card__head">
        <h2 id="dong-title" className="section-title">{t("route.dong.title")}</h2>
        <button type="button" className="chip" aria-pressed={on} onClick={() => setOn((v) => !v)}>
          {on ? t("route.dong.hide") : t("route.dong.show")}
        </button>
      </div>
      {on && state === "loading" && <p className="note">{t("route.dong.loading")}</p>}
      {on && state === "error" && <p className="field-error">{t("route.dong.error")}</p>}
      {on && state === "done" && data && (
        <>
          <p className="note">{t("route.dong.meaning")}</p>
          {data.unplacedPassengers > 0 && (
            <p className="note num">{t("route.dong.unplaced", { value: fmt(data.unplacedPassengers) })}</p>
          )}
          {!chosen ? (
            <p className="note">{t("route.dong.pick")}</p>
          ) : (
            <>
              <div className="dong-card__chosen">
                <strong>{chosen.name}</strong>
                <button type="button" className="chip" onClick={() => setSelected(null)}>
                  {t("route.dong.clear")}
                </button>
              </div>
              <p className="note num">
                {t("route.dong.totals", { on: fmt(chosen.boardings), off: fmt(chosen.alightings) })}
              </p>
              <div role="group" aria-label={t("route.dong.direction")} className="segmented segmented--fill">
                <button type="button" aria-pressed={direction === "from"} onClick={() => setDirection("from")}>
                  {t("route.dong.from")}
                </button>
                <button type="button" aria-pressed={direction === "to"} onClick={() => setDirection("to")}>
                  {t("route.dong.to")}
                </button>
              </div>
              {rows.length === 0 ? (
                <p className="note">{t("route.stop.none")}</p>
              ) : (
                <ol className="flow-list">
                  {rows.map((r) => (
                    <li key={r.code}>
                      <button type="button" className="link-button" onClick={() => setSelected(r.code)}>
                        {r.name}
                        {r.code === selected ? ` (${t("route.dong.inside")})` : ""}
                      </button>
                      <span className="num">{fmt(r.passengers)} · {pct(r.share)}</span>
                    </li>
                  ))}
                </ol>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}

/**
 * Frames the route once per route. The right-hand panel, when open, covers
 * part of the map on wide screens, so the fit leaves room for it.
 */
function FitRoute({ bounds, routeName, rightOpen, narrow }) {
  const map = useMap();
  const latestBounds = useLatest(bounds);
  const latestRightOpen = useLatest(rightOpen);
  const hasBounds = bounds !== null;
  useEffect(() => {
    const target = latestBounds.current;
    if (!target) return;
    const wide = map.getContainer().clientWidth > 900;
    map.fitBounds(target, {
      paddingTopLeft: [40, 40],
      // On phones the bottom sheet covers about 150 px of the map.
      paddingBottomRight: [wide && latestRightOpen.current ? 460 : 40, narrow ? 170 : 40],
      maxZoom: 15,
      // No zoom animation: a layer swapped during one (route colours ↔ demand
      // colours) left Leaflet drawing stale lines and the map stuck.
      animate: false
    });
    // Fit when the route changes, not on every re-render of its stops or a
    // panel being folded (folding only uncovers more map).
  }, [map, latestBounds, latestRightOpen, routeName, hasBounds, narrow]);
  return null;
}
