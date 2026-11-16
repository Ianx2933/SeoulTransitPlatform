import { useEffect, useMemo, useState } from "react";
import { CircleMarker, MapContainer, Polyline, TileLayer, Tooltip, useMap } from "react-leaflet";

import { fetchRouteStopPoints } from "../api/routeAnalysisApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { boundsOf, pathBetween, placeStops, splitAtTurnaround } from "../utils/routeGeometry.js";
import RouteAnalysisPanel from "./RouteAnalysisPanel.jsx";

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
  const [routeData, setRouteData] = useState(null);
  const [turnaround, setTurnaround] = useState(null);
  const [stopSeq, setStopSeq] = useState(null);
  const [reachSeq, setReachSeq] = useState(null);
  const [segment, setSegment] = useState(null);
  const [points, setPoints] = useState([]);
  const [pointsState, setPointsState] = useState("idle");

  const routeName = routeData?.노선명 ?? null;

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

  const placed = useMemo(() => placeStops(routeData?.stops || [], points), [routeData, points]);
  const lines = useMemo(() => splitAtTurnaround(placed, turnaround), [placed, turnaround]);
  const reachPath = useMemo(() => pathBetween(placed, stopSeq, reachSeq), [placed, stopSeq, reachSeq]);
  const segmentPath = useMemo(
    () => (segment ? pathBetween(placed, segment[0], segment[1]) : []),
    [placed, segment]
  );
  const bounds = useMemo(() => boundsOf(placed), [placed]);
  const selected = placed.find((p) => p.seq === stopSeq) || null;

  let note = t("route.map.empty");
  if (routeName && pointsState === "loading") note = t("route.map.loading");
  else if (routeName && pointsState === "error") note = t("route.map.error");
  else if (routeName && pointsState === "done") {
    note =
      placed.length === 0
        ? t("route.map.none")
        : t("route.map.placed", { placed: placed.length, all: routeData.stops.length });
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

          {segmentPath.length > 1 && (
            <Polyline positions={segmentPath} pathOptions={{ color: COLORS.segment, weight: 14, opacity: 0.9 }} />
          )}
          {lines.up.length > 1 && (
            <Polyline positions={lines.up} pathOptions={{ color: COLORS.up, weight: 5, opacity: 0.9 }} />
          )}
          {lines.down.length > 1 && (
            <Polyline positions={lines.down} pathOptions={{ color: COLORS.down, weight: 5, opacity: 0.95 }} />
          )}
          {reachPath.length > 1 && (
            <Polyline positions={reachPath} pathOptions={{ color: COLORS.selected, weight: 8, opacity: 0.95 }} />
          )}

          {placed.map((p) => {
            const down = turnaround != null && p.seq > turnaround;
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
      </div>
      <RouteAnalysisPanel
        busLines={busLines}
        stopSeq={stopSeq}
        onStopSeqChange={setStopSeq}
        onRouteData={setRouteData}
        onTurnaround={setTurnaround}
        onReach={setReachSeq}
        onSegment={setSegment}
      />
    </div>
  );
}

/**
 * Frames the route once per route. The right-hand panel covers part of the
 * map on wide screens, so the fit leaves room for it.
 */
function FitRoute({ bounds, routeName }) {
  const map = useMap();
  useEffect(() => {
    if (!bounds) return;
    const wide = map.getContainer().clientWidth > 900;
    map.fitBounds(bounds, {
      paddingTopLeft: [40, 40],
      paddingBottomRight: [wide ? 520 : 40, 40],
      maxZoom: 15
    });
    // Fit when the route changes, not on every re-render of its stops.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeName, bounds === null]);
  return null;
}
