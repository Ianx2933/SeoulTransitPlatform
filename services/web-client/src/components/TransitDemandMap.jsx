import { useEffect, useMemo, useState } from "react";
import {
  CircleMarker,
  GeoJSON,
  MapContainer,
  Popup,
  TileLayer
} from "react-leaflet";

import booleanPointInPolygon from "@turf/boolean-point-in-polygon";
import { point as turfPoint } from "@turf/helpers";

import {
  fetchMultiModeMapDemand,
  fetchNodeCatchment,
  fetchNodeDetail
} from "../api/mapDemandApi.js";
import NodeDetailPanel from "./NodeDetailPanel.jsx";

const SEOUL_CENTER = [37.5665, 126.9780];

const ROUTE_COLOR_PALETTE = [
  "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
  "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"
];

/**
 * Basemap tile layers compatible with Leaflet.
 */
const TILE_LAYERS = {
  osm: {
    label: "OpenStreetMap",
    url: "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    attribution: "&copy; OpenStreetMap contributors"
  },
  cartoLight: {
    label: "CartoDB Positron",
    url: "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png",
    attribution: "&copy; OpenStreetMap contributors &copy; CARTO"
  },
  cartoDark: {
    label: "CartoDB Dark Matter",
    url: "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png",
    attribution: "&copy; OpenStreetMap contributors &copy; CARTO"
  }
};

/**
 * Returns a stable color for each route.
 */
function getRouteColor(mode, serviceId) {
  const text = `${mode || "unknown"}-${serviceId || ""}`;
  let hash = 0;

  for (let index = 0; index < text.length; index += 1) {
    hash = text.charCodeAt(index) + ((hash << 5) - hash);
  }

  return ROUTE_COLOR_PALETTE[Math.abs(hash) % ROUTE_COLOR_PALETTE.length];
}

/**
 * Converts demand volume into a readable circle radius.
 */
function calculateRadius(point, metric) {
  const demand = getDemandValue(point, metric);
  return demand <= 0 ? 3 : Math.min(24, Math.max(4, Math.sqrt(demand) / 5));
}

/**
 * Returns demand value by selected metric.
 */
function getDemandValue(point, metric) {
  const boarding = Number(point.boarding || 0);
  const alighting = Number(point.alighting || 0);

  if (metric === "boarding") return boarding;
  if (metric === "alighting") return alighting;
  return boarding + alighting;
}

function getDistrictName(feature) {
  return feature?.properties?.ADM_NM || feature?.properties?.adm_nm || "Selected district";
}

function getDistrictCode(feature) {
  return feature?.properties?.ADM_CD || feature?.properties?.adm_cd || "";
}

function isValidPoint(point) {
  return Number.isFinite(Number(point.lat)) && Number.isFinite(Number(point.lng));
}

/**
 * Creates a stable React key for a loaded demand point.
 *
 * The key intentionally uses only (mode, serviceId, nodeId) so that the same
 * physical node is never split into multiple markers because of upstream
 * naming differences or sub-decimal coordinate drift between data sources.
 */
function createPointKey(point) {
  return [
    point.mode || "unknown",
    point.serviceId || "unknown",
    point.nodeId || "unknown"
  ].join("-");
}

/**
 * Creates a demand summary for the currently visible points.
 */
function summarizeDemand(points, metric) {
  const totals = points.reduce(
    (accumulator, point) => {
      const boarding = Number(point.boarding || 0);
      const alighting = Number(point.alighting || 0);
      accumulator.boarding += boarding;
      accumulator.alighting += alighting;
      accumulator.total += boarding + alighting;
      return accumulator;
    },
    { boarding: 0, alighting: 0, total: 0 }
  );

  const routeMap = new Map();

  points.forEach((point) => {
    const mode = point.mode || "unknown";
    const serviceId = point.serviceId || "unknown";
    const key = `${mode}-${serviceId}`;
    const existing = routeMap.get(key) || { key, mode, serviceId, boarding: 0, alighting: 0, total: 0 };
    existing.boarding += Number(point.boarding || 0);
    existing.alighting += Number(point.alighting || 0);
    existing.total += Number(point.boarding || 0) + Number(point.alighting || 0);
    routeMap.set(key, existing);
  });

  return {
    totals,
    routes: Array.from(routeMap.values()).sort((a, b) => Number(b[metric] || 0) - Number(a[metric] || 0))
  };
}

export default function TransitDemandMap({
  filters,
  showAdminBoundary = true,
  selectedTileLayer = "cartoLight"
}) {
  const [points, setPoints] = useState([]);
  const [adminDongGeoJson, setAdminDongGeoJson] = useState(null);
  const [selectedDistricts, setSelectedDistricts] = useState([]);
  const [selectedPoint, setSelectedPoint] = useState(null);
  const [nodeDetail, setNodeDetail] = useState(null);
  const [nodeCatchment, setNodeCatchment] = useState(null);
  const [nodeCatchmentRadiusMeters, setNodeCatchmentRadiusMeters] = useState(800);
  const [loading, setLoading] = useState(false);
  const [geoLoading, setGeoLoading] = useState(true);
  const [nodeDetailLoading, setNodeDetailLoading] = useState(false);
  const [nodeCatchmentLoading, setNodeCatchmentLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const [nodeDetailErrorMessage, setNodeDetailErrorMessage] = useState("");
  const [nodeCatchmentErrorMessage, setNodeCatchmentErrorMessage] = useState("");

  /**
   * Increments to force a manual retry of node-detail and catchment effects.
   *
   * Using a nonce is more explicit than the older trick of setting selectedPoint
   * to null and back inside a setTimeout. The intent — "rerun the effects" — is
   * directly encoded in the dependency array.
   */
  const [retryNonce, setRetryNonce] = useState(0);

  const metric = filters?.metric || "total";
  const tileLayer = TILE_LAYERS[selectedTileLayer] || TILE_LAYERS.cartoLight;

  const sortedPoints = useMemo(() => {
    return [...points].sort((a, b) => getDemandValue(b, metric) - getDemandValue(a, metric));
  }, [points, metric]);

  const visiblePoints = useMemo(() => {
    if (selectedDistricts.length === 0) return sortedPoints;

    return sortedPoints.filter((transitPoint) => {
      if (!isValidPoint(transitPoint)) return false;
      const candidatePoint = turfPoint([Number(transitPoint.lng), Number(transitPoint.lat)]);
      return selectedDistricts.some((district) => booleanPointInPolygon(candidatePoint, district));
    });
  }, [sortedPoints, selectedDistricts]);

  const selectedPointKey = selectedPoint ? createPointKey(selectedPoint) : "";
  const demandSummary = useMemo(() => summarizeDemand(visiblePoints, metric), [visiblePoints, metric]);
  const selectedDistrictCodes = useMemo(() => new Set(selectedDistricts.map(getDistrictCode)), [selectedDistricts]);

  /**
   * Combined error shown in the panel header.
   *
   * Both API errors are reported, separated by a line break, so a failure
   * in one API does not hide a failure in the other.
   */
  const combinedNodeErrorMessage = [nodeDetailErrorMessage, nodeCatchmentErrorMessage]
    .filter(Boolean)
    .join("\n");

  useEffect(() => {
    let ignore = false;

    async function loadAdminDongGeoJson() {
      setGeoLoading(true);
      try {
        const response = await fetch("/data/capital_area_admin_dong_4326.geojson");
        if (!response.ok) throw new Error(`Failed to load GeoJSON: ${response.status}`);
        const data = await response.json();
        if (!ignore) setAdminDongGeoJson(data);
      } catch (error) {
        if (!ignore) setErrorMessage(error.message);
      } finally {
        if (!ignore) setGeoLoading(false);
      }
    }

    loadAdminDongGeoJson();
    return () => { ignore = true; };
  }, []);

  useEffect(() => {
    if (!filters) {
      setPoints([]);
      setSelectedDistricts([]);
      clearNodeSelection();
      setErrorMessage("");
      return;
    }

    let ignore = false;

    async function loadDemand() {
      setLoading(true);
      setErrorMessage("");
      try {
        const data = await fetchMultiModeMapDemand({
          selectedSubwayLines: filters.selectedSubwayLines,
          selectedBusLines: filters.selectedBusLines,
          dayTypes: filters.dayTypes,
          dayAggregation: filters.dayAggregation,
          hours: filters.hours
        });
        if (!ignore) {
          setPoints(data);
          setSelectedDistricts([]);
          clearNodeSelection();
        }
      } catch (error) {
        if (!ignore) {
          setErrorMessage(error.message);
          setPoints([]);
          setSelectedDistricts([]);
          clearNodeSelection();
        }
      } finally {
        if (!ignore) setLoading(false);
      }
    }

    loadDemand();
    return () => { ignore = true; };
  }, [filters]);

  /**
   * Loads node-detail when the selected point or filters change.
   *
   * Catchment radius changes do not trigger this effect, because node-detail
   * is independent of radius. This avoids redundant API calls when the user
   * toggles between 400 / 800 / 1000m.
   */
  useEffect(() => {
    if (!selectedPoint || !filters) {
      setNodeDetail(null);
      setNodeDetailErrorMessage("");
      return;
    }

    let ignore = false;

    async function loadNodeDetail() {
      setNodeDetailLoading(true);
      setNodeDetailErrorMessage("");
      try {
        const nextNodeDetail = await fetchNodeDetail({
          mode: selectedPoint.mode,
          nodeId: selectedPoint.nodeId,
          dayTypes: filters.dayTypes,
          dayAggregation: filters.dayAggregation,
          hours: filters.hours
        });
        if (!ignore) {
          setNodeDetail(nextNodeDetail);
        }
      } catch (error) {
        if (!ignore) {
          setNodeDetail(null);
          setNodeDetailErrorMessage(error.message);
        }
      } finally {
        if (!ignore) setNodeDetailLoading(false);
      }
    }

    loadNodeDetail();
    return () => { ignore = true; };
  }, [selectedPoint, filters, retryNonce]);

  /**
   * Loads catchment whenever the selected point, filters, or radius change.
   *
   * Independent from the node-detail effect so a catchment failure does not
   * wipe out node-detail data already on screen.
   */
  useEffect(() => {
    if (!selectedPoint || !filters) {
      setNodeCatchment(null);
      setNodeCatchmentErrorMessage("");
      return;
    }

    let ignore = false;

    async function loadCatchment() {
      setNodeCatchmentLoading(true);
      setNodeCatchmentErrorMessage("");
      try {
        const nextCatchment = await fetchNodeCatchment({
          lat: selectedPoint.lat,
          lng: selectedPoint.lng,
          radiusMeters: nodeCatchmentRadiusMeters,
          modes: ["subway", "bus"],
          dayTypes: filters.dayTypes,
          dayAggregation: filters.dayAggregation,
          hours: filters.hours
        });
        if (!ignore) {
          setNodeCatchment(nextCatchment);
        }
      } catch (error) {
        if (!ignore) {
          setNodeCatchment(null);
          setNodeCatchmentErrorMessage(error.message);
        }
      } finally {
        if (!ignore) setNodeCatchmentLoading(false);
      }
    }

    loadCatchment();
    return () => { ignore = true; };
  }, [selectedPoint, filters, nodeCatchmentRadiusMeters, retryNonce]);

  function clearNodeSelection() {
    setSelectedPoint(null);
    setNodeDetail(null);
    setNodeCatchment(null);
    setNodeDetailErrorMessage("");
    setNodeCatchmentErrorMessage("");
  }

  function handlePointClick(point) {
    setSelectedPoint(point);
    setNodeDetail(null);
    setNodeCatchment(null);
    setNodeDetailErrorMessage("");
    setNodeCatchmentErrorMessage("");
  }

  function handleRetryNodeAnalysis() {
    if (!selectedPoint) return;
    setRetryNonce((current) => current + 1);
  }

  const toggleDistrictSelection = (feature, event) => {
    const code = getDistrictCode(feature);
    const multiSelect = event.originalEvent.ctrlKey || event.originalEvent.shiftKey || event.originalEvent.metaKey;

    if (!multiSelect) {
      setSelectedDistricts([feature]);
      clearNodeSelection();
      return;
    }

    setSelectedDistricts((currentDistricts) => {
      const alreadySelected = currentDistricts.some((district) => getDistrictCode(district) === code);
      if (alreadySelected) {
        return currentDistricts.filter((district) => getDistrictCode(district) !== code);
      }
      return [...currentDistricts, feature];
    });
    clearNodeSelection();
  };

  const handleEachDistrict = (feature, layer) => {
    layer.on({ click: (event) => toggleDistrictSelection(feature, event) });
    layer.bindTooltip(getDistrictName(feature), { sticky: true });
  };

  const getDistrictStyle = (feature) => {
    const isSelected = selectedDistrictCodes.has(getDistrictCode(feature));
    return { color: isSelected ? "#111111" : "#444444", weight: isSelected ? 3 : 1, fillOpacity: isSelected ? 0.14 : 0.04 };
  };

  return (
    <div className="map-page">
      {errorMessage && <div className="error-box">API error: {errorMessage}</div>}

      <div className="map-status-bar" style={{ display: "flex", gap: "12px", alignItems: "center", flexWrap: "wrap", padding: "8px 16px", fontSize: "14px" }}>
        <span>
          {geoLoading && "Loading administrative boundaries..."}
          {!geoLoading && !filters && "Select filters and click Load demand."}
          {filters && loading && "Loading demand data..."}
          {filters && !loading && `${visiblePoints.length.toLocaleString()} / ${points.length.toLocaleString()} points shown`}
        </span>

        {selectedDistricts.length > 0 && (
          <>
            <strong>Districts: {selectedDistricts.map(getDistrictName).join(", ")}</strong>
            <button type="button" onClick={() => { setSelectedDistricts([]); clearNodeSelection(); }}>Clear districts</button>
          </>
        )}
      </div>

      {filters && !loading && (
        <section className="demand-summary" style={{ padding: "12px 16px", borderBottom: "1px solid #dddddd", backgroundColor: "#ffffff" }}>
          <strong>Demand summary{filters?.dayAggregation === "sum" ? " — sum of selected days" : " — average per selected day"}</strong>
          <div>
            Boarding: {demandSummary.totals.boarding.toLocaleString()} | Alighting: {demandSummary.totals.alighting.toLocaleString()} | Total: {demandSummary.totals.total.toLocaleString()}
          </div>

          <div style={{ marginTop: "8px" }}>
            <strong>Routes by {metric}</strong>
            <span style={{ marginLeft: "8px", color: "#666666" }}>({demandSummary.routes.length.toLocaleString()} route{demandSummary.routes.length === 1 ? "" : "s"})</span>
            <ol style={{ maxHeight: "280px", overflowY: "auto", paddingRight: "12px" }}>
              {demandSummary.routes.map((route) => (
                <li key={route.key}>
                  <span style={{ display: "inline-block", width: "10px", height: "10px", borderRadius: "50%", marginRight: "6px", backgroundColor: getRouteColor(route.mode, route.serviceId) }} />
                  [{route.mode}] {route.serviceId} — {Number(route[metric] || 0).toLocaleString()}
                </li>
              ))}
            </ol>
          </div>
        </section>
      )}

      <NodeDetailPanel
        selectedPoint={selectedPoint}
        nodeDetail={nodeDetail}
        catchment={nodeCatchment}
        radiusMeters={nodeCatchmentRadiusMeters}
        onRadiusMetersChange={setNodeCatchmentRadiusMeters}
        loading={nodeDetailLoading}
        catchmentLoading={nodeCatchmentLoading}
        errorMessage={combinedNodeErrorMessage}
        onRetry={handleRetryNodeAnalysis}
        onClose={clearNodeSelection}
      />

      <section className="map-card">
        <MapContainer center={SEOUL_CENTER} zoom={10} scrollWheelZoom className="leaflet-map">
          <TileLayer key={selectedTileLayer} attribution={tileLayer.attribution} url={tileLayer.url} />

          {showAdminBoundary && adminDongGeoJson && (
            <GeoJSON key={selectedDistricts.map(getDistrictCode).join("-") || "all"} data={adminDongGeoJson} style={getDistrictStyle} onEachFeature={handleEachDistrict} />
          )}

          {visiblePoints.map((point) => {
            const color = getRouteColor(point.mode, point.serviceId);
            const key = createPointKey(point);
            const isSelectedPoint = key === selectedPointKey;
            const radius = calculateRadius(point, metric);

            return (
              <CircleMarker
                key={key}
                center={[point.lat, point.lng]}
                radius={isSelectedPoint ? radius + 4 : radius}
                pathOptions={{ color, fillColor: color, weight: isSelectedPoint ? 4 : 1, opacity: isSelectedPoint ? 1 : 0.85, fillOpacity: isSelectedPoint ? 0.9 : 0.5 }}
                eventHandlers={{ click: () => handlePointClick(point) }}
              >
                <Popup>
                  <strong>{point.nodeName}</strong><br />
                  Mode: {point.mode}<br />
                  Line: {point.serviceId}<br />
                  Node ID: {point.nodeId}<br />
                  Boarding: {Number(point.boarding || 0).toLocaleString()}<br />
                  Alighting: {Number(point.alighting || 0).toLocaleString()}<br />
                  Total: {(Number(point.boarding || 0) + Number(point.alighting || 0)).toLocaleString()}
                </Popup>
              </CircleMarker>
            );
          })}
        </MapContainer>
      </section>
    </div>
  );
}
