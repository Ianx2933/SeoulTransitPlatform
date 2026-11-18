import { useEffect, useMemo, useState } from "react";
import { CircleMarker, GeoJSON, MapContainer, Pane, Polyline, TileLayer, Tooltip, useMap } from "react-leaflet";

import { fetchRouteDongOd, fetchRoutePath, fetchRouteStopPoints } from "../api/routeAnalysisApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { shade, shadeValues, topRows } from "../utils/dongFlows.js";
import { busRouteStyle } from "../utils/busRouteType.js";
import {
  boundsOf,
  pathBetween,
  pathStopPoints,
  placeStops,
  seqOfArs,
  splitAtTurnaround
} from "../utils/routeGeometry.js";
import { findTurnaround } from "../utils/routeLoadChart.js";
import { useRoadLoad } from "../hooks/useRoadLoad.js";
import { useStoredNumber } from "../hooks/useStoredNumber.js";
import useLatest from "../hooks/useLatest.js";
import { DEFAULT_MIN_LOAD, styleSegments } from "../utils/routeLoad.js";
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
  const { t } = useI18n();
  const translate = useLatest(t);
  const [routeData, setRouteData] = useState(null);
  const [turnaround, setTurnaround] = useState(null);
  const [stopSeq, setStopSeq] = useState(null);
  const [reachSeq, setReachSeq] = useState(null);
  const [segment, setSegment] = useState(null);
  const [points, setPoints] = useState([]);
  const [pointsState, setPointsState] = useState("idle");
  const [query, setQuery] = useState({ dayType: "weekday", hour: null });
  const [roadPath, setRoadPath] = useState(null);
  const [dongOn, setDongOn] = useState(false);
  const [dongData, setDongData] = useState(null);
  const [dongState, setDongState] = useState("idle");
  const [dongSelected, setDongSelected] = useState(null);
  const [dongDirection, setDongDirection] = useState("from");

  const routeName = routeData?.노선명 ?? null;

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
    roadOn && routeName ? { kind: "routes", routes: [routeName, ...extraRoutes], dayType: query.dayType } : null;
  const roadLoad = useRoadLoad(roadRequest);
  const exploreLoad = useRoadLoad(roadOn ? explore : null);
  const roadStyled = useMemo(() => {
    if (exploreLoad.result) return styleSegments(exploreLoad.result.segments, { minLoad: roadMinLoad });
    if (!roadLoad.result) return null;
    const known = new Set((roadLoad.result.routes || []));
    return styleSegments(roadLoad.result.segments, {
      minLoad: roadMinLoad,
      routes: roadPicked.filter((r) => known.has(r))
    });
  }, [roadLoad.result, exploreLoad.result, roadMinLoad, roadPicked]);

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
        if (!ignore) setRoadPath({ path: [], turnaroundArs: null });
      });
    return () => {
      ignore = true;
    };
  }, [routeName]);

  const turnaroundFromBis = useMemo(() => {
    if (!roadPath?.turnaroundArs || !routeData) return null;
    return seqOfArs(routeData.stops, roadPath.turnaroundArs, findTurnaround(routeData.stops));
  }, [roadPath, routeData]);
  const hasRoad = Array.isArray(roadPath?.path) && roadPath.path.length > 1;
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
      hour: query.hour,
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
  }, [translate, dongOn, routeName, query.dayType, query.hour]);

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
    () => placeStops(routeData?.stops || [], [...points, ...pathStopPoints(roadPath)]),
    [routeData, points, roadPath]
  );
  const effectiveTurnaround = turnaroundFromBis ?? turnaround;
  const lines = useMemo(() => splitAtTurnaround(placed, effectiveTurnaround), [placed, effectiveTurnaround]);
  const reachPath = useMemo(() => pathBetween(placed, stopSeq, reachSeq), [placed, stopSeq, reachSeq]);
  const segmentPath = useMemo(
    () => (segment ? pathBetween(placed, segment[0], segment[1]) : []),
    [placed, segment]
  );
  const bounds = useMemo(() => {
    if (hasRoad) return boundsOf(roadPath.path.map(([lat, lng]) => ({ lat, lng })));
    return boundsOf(placed);
  }, [placed, hasRoad, roadPath]);
  const selected = placed.find((p) => p.seq === stopSeq) || null;

  let note = t("route.map.empty");
  if (routeName && pointsState === "loading") note = t("route.map.loading");
  else if (routeName && pointsState === "error") note = t("route.map.error");
  else if (routeName && pointsState === "done") {
    note =
      placed.length === 0
        ? t("route.map.none")
        : t("route.map.placed", { placed: placed.length, all: routeData.stops.length });
    if (roadPath) note += " " + (hasRoad ? t("route.map.road") : t("route.map.straight"));
  }

  return (
    <div className="route-view">
      <div className="route-view__map">
        <MapContainer center={SEOUL_CENTER} zoom={12} className="route-view__leaflet" scrollWheelZoom>
          <TileLayer
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          />
          <FitRoute bounds={bounds} routeName={routeName} />

          <Pane name="districts" style={{ zIndex: 350 }}>
            {/* The road view draws riders itself; district fills would hide it. */}
            {dongOn && !roadOn && dongFeatures.length > 0 && (
              <GeoJSON
                key={`${dongData.노선명}-${dongData.기준일자}-${dongData.hour}-${dongSelected}-${dongDirection}`}
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
            <Polyline positions={segmentPath} pathOptions={{ color: COLORS.segment, weight: 14, opacity: 0.9 }} />
          )}
          {!roadOn && hasRoad && variantPaths.length === 0 && (
            <Polyline positions={roadPath.path} pathOptions={{ color: roadColor, weight: 5, opacity: 0.85 }} />
          )}
          {/* A route stored as several runs (한강버스01, 8762 출근/퇴근): draw each. */}
          {!roadOn &&
            variantPaths.map((v) => (
              <Polyline key={v.name} positions={v.path} pathOptions={{ color: roadColor, weight: 5, opacity: 0.85 }}>
                <Tooltip sticky>{v.name}</Tooltip>
              </Polyline>
            ))}
          {!roadOn && !hasRoad && lines.up.length > 1 && (
            <Polyline positions={lines.up} pathOptions={{ color: COLORS.up, weight: 5, opacity: 0.9 }} />
          )}
          {!roadOn && !hasRoad && lines.down.length > 1 && (
            <Polyline positions={lines.down} pathOptions={{ color: COLORS.down, weight: 5, opacity: 0.95 }} />
          )}
          {roadOn && roadStyled && (
            <RouteLoadLayer
              result={exploreLoad.result || roadLoad.result}
              segments={roadStyled.segments}
              onSegmentClick={(s) => setExplore({ kind: "segment", from: s.fromArs, to: s.toArs, dayType: query.dayType })}
            />
          )}
          {roadOn && exploreLoad.result && <RoadPointsLayer result={exploreLoad.result} />}
          {reachPath.length > 1 && (
            <Polyline positions={reachPath} pathOptions={{ color: COLORS.selected, weight: 8, opacity: 0.95 }} />
          )}

          {placed.map((p) => {
            const down = effectiveTurnaround != null && p.seq > effectiveTurnaround;
            return (
              <CircleMarker
                key={p.seq}
                center={[p.lat, p.lng]}
                radius={4}
                pathOptions={{ color: down ? COLORS.down : COLORS.up, weight: 2, fillColor: "#ffffff", fillOpacity: 1 }}
                eventHandlers={{ click: () => setStopSeq(p.seq) }}
              >
                <Tooltip direction="top" offset={[0, -4]}>
                  {p.seq} {p.name}
                </Tooltip>
              </CircleMarker>
            );
          })}

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
        </MapContainer>
        <p className="map-note" role="status">{note}</p>
        {routeName && (
          <div className="route-cards">
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
            onStopLookup={(direction) => {
              const stop = routeData?.stops?.find((s) => s.seq === stopSeq);
              if (stop?.ars) setExplore({ kind: "stop", ars: String(stop.ars), direction, dayType: query.dayType });
            }}
          />
          {roadOn && (
            <RoadExploreCard
              request={explore}
              state={exploreLoad.state}
              error={exploreLoad.error}
              result={exploreLoad.result}
              onClose={() => setExplore(null)}
              onCombineRoutes={(routes) => {
                setExtraRoutes(routes.filter((r) => r !== routeName).slice(0, MAX_COMBINED_ROUTES - 1));
                setRoadPicked([]);
                setExplore(null);
              }}
            />
          )}
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
      <RouteAnalysisPanel
        busLines={busLines}
        stopSeq={stopSeq}
        onStopSeqChange={setStopSeq}
        onRouteData={setRouteData}
        onTurnaround={setTurnaround}
        onReach={setReachSeq}
        onSegment={setSegment}
        onQuery={setQuery}
        turnaroundFromBis={turnaroundFromBis}
      />
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
 * Frames the route once per route. The right-hand panel covers part of the
 * map on wide screens, so the fit leaves room for it.
 */
function FitRoute({ bounds, routeName }) {
  const map = useMap();
  const latestBounds = useLatest(bounds);
  const hasBounds = bounds !== null;
  useEffect(() => {
    const target = latestBounds.current;
    if (!target) return;
    const wide = map.getContainer().clientWidth > 900;
    map.fitBounds(target, {
      paddingTopLeft: [40, 40],
      paddingBottomRight: [wide ? 520 : 40, 40],
      maxZoom: 15
    });
    // Fit when the route changes, not on every re-render of its stops.
  }, [map, latestBounds, routeName, hasBounds]);
  return null;
}
