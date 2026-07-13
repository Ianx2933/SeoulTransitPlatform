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
  fetchDistrictDemand,
  fetchMultiModeMapDemand,
  fetchNodeCatchment,
  fetchNodeDetail
} from "../api/mapDemandApi.js";
import DistrictDemandPanel from "./DistrictDemandPanel.jsx";
import NodeDetailPanel from "./NodeDetailPanel.jsx";

const SEOUL_CENTER = [37.5665, 126.9780];

const ROUTE_COLOR_PALETTE = [
  "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
  "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"
];

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

function getDemandValue(point, metric) {
  const boarding = Number(point.boarding || 0);
  const alighting = Number(point.alighting || 0);
  if (metric === "boarding") return boarding;
  if (metric === "alighting") return alighting;
  return boarding + alighting;
}

function getDistrictName(feature) {
  return (
    feature?.properties?.ADM_NM ||
    feature?.properties?.adm_nm ||
    "Selected district"
  );
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
 * Key uses only (mode, serviceId, nodeId) so upstream naming differences or
 * sub-decimal coordinate drift do not split the same node into separate markers.
 */
function createPointKey(point) {
  return [
    point.mode || "unknown",
    point.serviceId || "unknown",
    point.nodeId || "unknown"
  ].join("-");
}

/**
 * Adapts a search result into the selectedPoint shape consumed by the panel.
 */
function createSelectedPointFromSearchNode(node) {
  if (!node) return null;
  return {
    mode: node.mode,
    serviceId: "node-search",
    nodeId: node.nodeId,
    nodeName: node.nodeName,
    lat: node.lat,
    lng: node.lng,
    boarding: 0,
    alighting: 0,
    source: "node-search"
  };
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
    const existing = routeMap.get(key) || {
      key,
      mode,
      serviceId,
      boarding: 0,
      alighting: 0,
      total: 0
    };
    existing.boarding += Number(point.boarding || 0);
    existing.alighting += Number(point.alighting || 0);
    existing.total +=
      Number(point.boarding || 0) + Number(point.alighting || 0);
    routeMap.set(key, existing);
  });

  return {
    totals,
    routes: Array.from(routeMap.values()).sort(
      (a, b) => Number(b[metric] || 0) - Number(a[metric] || 0)
    )
  };
}

export default function TransitDemandMap({
  filters,
  showAdminBoundary = true,
  selectedTileLayer = "cartoLight",
  selectedSearchNode,
  onClearSearchNode,
  selectedSubwayLines = [],
  selectedBusLines = [],
  onAddRouteFromNode,
  showCatchmentMarkers = true,
  onShowCatchmentMarkersChange
}) {
  const [points, setPoints] = useState([]);
  const [adminDongGeoJson, setAdminDongGeoJson] = useState(null);
  const [selectedDistricts, setSelectedDistricts] = useState([]);
  const [selectedPoint, setSelectedPoint] = useState(null);
  const [nodeDetail, setNodeDetail] = useState(null);
  const [nodeCatchment, setNodeCatchment] = useState(null);
  const [districtDemand, setDistrictDemand] = useState(null);
  const [nodeCatchmentRadiusMeters, setNodeCatchmentRadiusMeters] = useState(800);

  const [analysisSections, setAnalysisSections] = useState({
    showSelectedPoint: true,
    showNodeDetail: true,
    showCatchment: true,
    showNearbyNodes: true,
    showRoutes: true
  });

  const [loading, setLoading] = useState(false);
  const [geoLoading, setGeoLoading] = useState(true);
  const [nodeDetailLoading, setNodeDetailLoading] = useState(false);
  const [nodeCatchmentLoading, setNodeCatchmentLoading] = useState(false);
  const [districtDemandLoading, setDistrictDemandLoading] = useState(false);

  const [errorMessage, setErrorMessage] = useState("");
  const [nodeDetailErrorMessage, setNodeDetailErrorMessage] = useState("");
  const [nodeCatchmentErrorMessage, setNodeCatchmentErrorMessage] = useState("");
  const [districtDemandErrorMessage, setDistrictDemandErrorMessage] = useState("");

  const [retryNonce, setRetryNonce] = useState(0);
  const [districtRetryNonce, setDistrictRetryNonce] = useState(0);

  const metric = filters?.metric || "total";
  const tileLayer = TILE_LAYERS[selectedTileLayer] || TILE_LAYERS.cartoLight;

  // Catchment data should load when any catchment-derived display is enabled.
  // showCatchment now means "summary display", not a master switch for nodes/routes.
  const shouldLoadCatchment =
    analysisSections.showCatchment ||
    analysisSections.showNearbyNodes ||
    analysisSections.showRoutes;

  // Primitive serializations used as effect dependencies so React's Object.is
  // comparison sees stable identity across renders. Putting the filters object
  // directly into a dependency array would re-fire the effect on every parent
  // render even when the underlying values are unchanged.
  const filtersDayTypesKey = filters?.dayTypes?.join(",") || "";
  const filtersHoursKey = filters?.hours?.join(",") || "";
  const filtersDayAggregation = filters?.dayAggregation || "";
  const filtersApplied = Boolean(filters);

  const sortedPoints = useMemo(
    () =>
      [...points].sort(
        (a, b) => getDemandValue(b, metric) - getDemandValue(a, metric)
      ),
    [points, metric]
  );

  const visiblePoints = useMemo(() => {
    if (selectedDistricts.length === 0) return sortedPoints;
    return sortedPoints.filter((transitPoint) => {
      if (!isValidPoint(transitPoint)) return false;
      const candidatePoint = turfPoint([
        Number(transitPoint.lng),
        Number(transitPoint.lat)
      ]);
      return selectedDistricts.some((district) =>
        booleanPointInPolygon(candidatePoint, district)
      );
    });
  }, [sortedPoints, selectedDistricts]);

  const selectedPointKey = selectedPoint ? createPointKey(selectedPoint) : "";

  const demandSummary = useMemo(
    () => summarizeDemand(visiblePoints, metric),
    [visiblePoints, metric]
  );

  const selectedDistrictSummaries = useMemo(
    () =>
      selectedDistricts.map((feature) => ({
        districtCode: getDistrictCode(feature),
        districtName: getDistrictName(feature)
      })),
    [selectedDistricts]
  );

  const selectedDistrictCodeList = useMemo(
    () =>
      selectedDistrictSummaries
        .map((district) => district.districtCode)
        .filter(Boolean),
    [selectedDistrictSummaries]
  );

  const selectedDistrictCodesKey = selectedDistrictCodeList.join(",");

  const selectedDistrictCodes = useMemo(
    () => new Set(selectedDistrictCodeList),
    [selectedDistrictCodeList]
  );

  const combinedNodeErrorMessage = [
    nodeDetailErrorMessage,
    nodeCatchmentErrorMessage
  ]
    .filter(Boolean)
    .join("\n");

  useEffect(() => {
    let ignore = false;
    async function loadAdminDongGeoJson() {
      setGeoLoading(true);
      try {
        const response = await fetch("/data/capital_area_admin_dong_4326.geojson");
        if (!response.ok) {
          throw new Error(`Failed to load GeoJSON: ${response.status}`);
        }
        const data = await response.json();
        if (!ignore) setAdminDongGeoJson(data);
      } catch (error) {
        if (!ignore) setErrorMessage(error.message);
      } finally {
        if (!ignore) setGeoLoading(false);
      }
    }
    loadAdminDongGeoJson();
    return () => {
      ignore = true;
    };
  }, []);

  // When boundary is turned off, drop any selected districts so the map and
  // demand summary stop being filtered by an invisible selection.
  useEffect(() => {
    if (!showAdminBoundary && selectedDistricts.length > 0) {
      setSelectedDistricts([]);
    }
  }, [showAdminBoundary, selectedDistricts.length]);

  // Adopt a search-driven node as the analysis target.
  useEffect(() => {
    if (!selectedSearchNode) return;
    setSelectedPoint(createSelectedPointFromSearchNode(selectedSearchNode));
    setNodeDetail(null);
    setNodeCatchment(null);
    setNodeDetailErrorMessage("");
    setNodeCatchmentErrorMessage("");
  }, [selectedSearchNode]);

  // Loads demand points whenever appliedFilters changes.
  useEffect(() => {
    if (!filters) {
      setPoints([]);
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
        // Selected districts are intentionally preserved across Load demand:
        // the district-demand effect below refetches with the new filter keys,
        // and visiblePoints keeps filtering the fresh map layer by the same
        // polygons.
        if (!ignore) {
          setPoints(data);
        }
      } catch (error) {
        if (!ignore) {
          setErrorMessage(error.message);
          setPoints([]);
        }
      } finally {
        if (!ignore) setLoading(false);
      }
    }
    loadDemand();
    return () => {
      ignore = true;
    };
  }, [filters]);

  // Loads node-detail when selectedPoint or applied filters change.
  useEffect(() => {
    if (!selectedPoint || !filters || !analysisSections.showNodeDetail) {
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
        if (!ignore) setNodeDetail(nextNodeDetail);
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
    return () => {
      ignore = true;
    };
    // Depend on primitive serializations of filter contents rather than the
    // filters object itself, to avoid re-firing on every parent re-render.
  }, [
    selectedPoint,
    filtersDayTypesKey,
    filtersHoursKey,
    filtersDayAggregation,
    analysisSections.showNodeDetail,
    retryNonce
  ]);

  // Loads catchment whenever selectedPoint, applied filters, or radius change.
  useEffect(() => {
    if (!selectedPoint || !filters || !shouldLoadCatchment) {
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
        if (!ignore) setNodeCatchment(nextCatchment);
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
    return () => {
      ignore = true;
    };
  }, [
    selectedPoint,
    filtersDayTypesKey,
    filtersHoursKey,
    filtersDayAggregation,
    nodeCatchmentRadiusMeters,
    shouldLoadCatchment,
    retryNonce
  ]);


  // Loads district-centered demand when district selection and applied filters exist.
  useEffect(() => {
    if (!showAdminBoundary || selectedDistrictCodeList.length === 0 || !filters) {
      setDistrictDemand(null);
      setDistrictDemandErrorMessage("");
      setDistrictDemandLoading(false);
      return;
    }

    let ignore = false;
    async function loadDistrictDemand() {
      setDistrictDemandLoading(true);
      setDistrictDemandErrorMessage("");
      try {
        const nextDistrictDemand = await fetchDistrictDemand({
          districtCodes: selectedDistrictCodeList,
          modes: ["subway", "bus"],
          dayTypes: filters.dayTypes,
          dayAggregation: filters.dayAggregation,
          hours: filters.hours
        });
        if (!ignore) setDistrictDemand(nextDistrictDemand);
      } catch (error) {
        if (!ignore) {
          setDistrictDemand(null);
          setDistrictDemandErrorMessage(error.message);
        }
      } finally {
        if (!ignore) setDistrictDemandLoading(false);
      }
    }

    loadDistrictDemand();
    return () => {
      ignore = true;
    };
  }, [
    showAdminBoundary,
    selectedDistrictCodesKey,
    filtersDayTypesKey,
    filtersHoursKey,
    filtersDayAggregation,
    filtersApplied,
    districtRetryNonce
  ]);

  function clearNodeSelection() {
    setSelectedPoint(null);
    setNodeDetail(null);
    setNodeCatchment(null);
    setNodeDetailErrorMessage("");
    setNodeCatchmentErrorMessage("");
    // Also clear the parent's search-node state so subsequent search clicks
    // on the same node reliably re-fire the adopt effect.
    onClearSearchNode?.();
  }

  // Clears selected districts and district-demand state together.
  function clearDistrictSelection() {
    setSelectedDistricts([]);
    setDistrictDemand(null);
    setDistrictDemandErrorMessage("");
    clearNodeSelection();
  }

  function handlePointClick(point) {
    setSelectedPoint({ ...point, source: "map-marker" });
    setNodeDetail(null);
    setNodeCatchment(null);
    setNodeDetailErrorMessage("");
    setNodeCatchmentErrorMessage("");
    // Clear the search-driven node so a stale search selection does not
    // override the map-marker click on the next effect run.
    onClearSearchNode?.();
  }

  function handleRetryNodeAnalysis() {
    if (selectedPoint) setRetryNonce((current) => current + 1);
  }

  function handleRetryDistrictDemand() {
    if (selectedDistrictCodeList.length > 0) {
      setDistrictRetryNonce((current) => current + 1);
    }
  }

  function handleToggleAnalysisSection(sectionKey) {
    setAnalysisSections((current) => ({
      ...current,
      [sectionKey]: !current[sectionKey]
    }));
  }

  const toggleDistrictSelection = (feature, event) => {
    if (!showAdminBoundary) return;
    const code = getDistrictCode(feature);
    const multiSelect =
      event.originalEvent.ctrlKey ||
      event.originalEvent.shiftKey ||
      event.originalEvent.metaKey;

    if (!multiSelect) {
      setSelectedDistricts([feature]);
      clearNodeSelection();
      return;
    }
    setSelectedDistricts((currentDistricts) => {
      const alreadySelected = currentDistricts.some(
        (district) => getDistrictCode(district) === code
      );
      if (alreadySelected) {
        return currentDistricts.filter(
          (district) => getDistrictCode(district) !== code
        );
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
    return {
      color: isSelected ? "#111111" : "#444444",
      weight: isSelected ? 3 : 1,
      fillOpacity: isSelected ? 0.14 : 0.04
    };
  };

  return (
    <div className="map-page">
      {errorMessage && (
        <div className="error-box">API error: {errorMessage}</div>
      )}

      <div
        className="map-status-bar"
        style={{
          display: "flex",
          gap: "12px",
          alignItems: "center",
          flexWrap: "wrap",
          padding: "8px 16px",
          fontSize: "14px"
        }}
      >
        <span>
          {geoLoading && "Loading administrative boundaries..."}
          {!geoLoading &&
            !filters &&
            points.length === 0 &&
            "Select filters and click Load demand, or search a stop/station."}
          {filters && loading && "Loading demand data..."}
          {filters &&
            !loading &&
            points.length === 0 &&
            "No demand layer loaded. Node analysis uses node-detail and catchment APIs directly."}
          {filters &&
            !loading &&
            points.length > 0 &&
            `${visiblePoints.length.toLocaleString()} / ${points.length.toLocaleString()} points shown`}
        </span>

        {filters?.autoApplied && (
          <span
            style={{
              color: "#7a4a00",
              backgroundColor: "#fff7e0",
              border: "1px solid #f0c060",
              padding: "2px 8px",
              borderRadius: "4px"
            }}
          >
            Filters auto-applied from current draft (no routes loaded). Adjust
            filters and click Load demand to update the map layer.
          </span>
        )}

        {selectedPoint && nodeCatchment?.nodes?.length > 0 && (
          <label
            style={{ display: "flex", gap: "4px", alignItems: "center" }}
          >
            <input
              type="checkbox"
              checked={showCatchmentMarkers}
              onChange={(event) =>
                onShowCatchmentMarkersChange?.(event.target.checked)
              }
            />
            Show catchment markers on map
          </label>
        )}

        {!showAdminBoundary && <span>Administrative boundary disabled.</span>}

        {showAdminBoundary && selectedDistricts.length > 0 && (
          <>
            <strong>
              Map demand summary is filtered by districts:{" "}
              {selectedDistricts.map(getDistrictName).join(", ")}
            </strong>
            <button
              type="button"
              onClick={clearDistrictSelection}
            >
              Clear districts
            </button>
          </>
        )}
      </div>

      {filters && !loading && points.length > 0 && (
        <section
          className="demand-summary"
          style={{
            padding: "12px 16px",
            borderBottom: "1px solid #dddddd",
            backgroundColor: "#ffffff"
          }}
        >
          <strong>
            Demand summary
            {selectedDistricts.length > 0
              ? " — filtered by selected districts"
              : ""}
            {filters?.dayAggregation === "sum"
              ? " — sum of selected days"
              : " — average per selected day"}
          </strong>
          <div>
            Boarding: {demandSummary.totals.boarding.toLocaleString()} |
            Alighting: {demandSummary.totals.alighting.toLocaleString()} | Total:{" "}
            {demandSummary.totals.total.toLocaleString()}
          </div>

          <div style={{ marginTop: "8px" }}>
            <strong>Routes by {metric}</strong>
            <span style={{ marginLeft: "8px", color: "#666666" }}>
              ({demandSummary.routes.length.toLocaleString()} route
              {demandSummary.routes.length === 1 ? "" : "s"})
            </span>
            <ol
              style={{
                maxHeight: "280px",
                overflowY: "auto",
                paddingRight: "12px"
              }}
            >
              {demandSummary.routes.map((route) => (
                <li key={route.key}>
                  <span
                    style={{
                      display: "inline-block",
                      width: "10px",
                      height: "10px",
                      borderRadius: "50%",
                      marginRight: "6px",
                      backgroundColor: getRouteColor(route.mode, route.serviceId)
                    }}
                  />
                  [{route.mode}] {route.serviceId} —{" "}
                  {Number(route[metric] || 0).toLocaleString()}
                </li>
              ))}
            </ol>
          </div>
        </section>
      )}

      <DistrictDemandPanel
        selectedDistricts={selectedDistrictSummaries}
        districtDemand={districtDemand}
        filtersApplied={filtersApplied}
        loading={districtDemandLoading}
        errorMessage={districtDemandErrorMessage}
        onRetry={handleRetryDistrictDemand}
        onClear={clearDistrictSelection}
        selectedSubwayLines={selectedSubwayLines}
        selectedBusLines={selectedBusLines}
        onAddRouteFromNode={onAddRouteFromNode}
      />

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
        sections={analysisSections}
        onToggleSection={handleToggleAnalysisSection}
        selectedSubwayLines={selectedSubwayLines}
        selectedBusLines={selectedBusLines}
        onAddRouteFromNode={onAddRouteFromNode}
      />

      <section className="map-card">
        <MapContainer
          center={SEOUL_CENTER}
          zoom={10}
          scrollWheelZoom
          className="leaflet-map"
        >
          <TileLayer
            key={selectedTileLayer}
            attribution={tileLayer.attribution}
            url={tileLayer.url}
          />

          {showAdminBoundary && adminDongGeoJson && (
            <GeoJSON
              key={selectedDistricts.map(getDistrictCode).join("-") || "all"}
              data={adminDongGeoJson}
              style={getDistrictStyle}
              onEachFeature={handleEachDistrict}
            />
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
                pathOptions={{
                  color,
                  fillColor: color,
                  weight: isSelectedPoint ? 4 : 1,
                  opacity: isSelectedPoint ? 1 : 0.85,
                  fillOpacity: isSelectedPoint ? 0.9 : 0.5
                }}
                eventHandlers={{ click: () => handlePointClick(point) }}
              >
                <Popup>
                  <strong>{point.nodeName}</strong>
                  <br />
                  Mode: {point.mode}
                  <br />
                  Line: {point.serviceId}
                  <br />
                  Node ID: {point.nodeId}
                  <br />
                  Boarding: {Number(point.boarding || 0).toLocaleString()}
                  <br />
                  Alighting: {Number(point.alighting || 0).toLocaleString()}
                  <br />
                  Total:{" "}
                  {(
                    Number(point.boarding || 0) + Number(point.alighting || 0)
                  ).toLocaleString()}
                </Popup>
              </CircleMarker>
            );
          })}

          {showCatchmentMarkers &&
            selectedPoint &&
            nodeCatchment?.nodes?.map((node) => {
              // Skip catchment marker at the selected point's own location;
              // the selected marker (rendered separately below) already shows it.
              if (
                Number(node.distanceMeters || 0) < 1 &&
                node.nodeId === selectedPoint.nodeId
              ) {
                return null;
              }
              return (
                <CircleMarker
                  key={`catchment-${node.mode}-${node.nodeId}`}
                  center={[node.lat, node.lng]}
                  radius={6}
                  pathOptions={{
                    color: "#555555",
                    fillColor: "#ffffff",
                    weight: 1.5,
                    opacity: 0.9,
                    fillOpacity: 0.4,
                    dashArray: "3,2"
                  }}
                  pane="markerPane"
                >
                  <Popup>
                    <strong>{node.nodeName}</strong>
                    <br />
                    <em style={{ color: "#666666" }}>
                      Catchment node — within {nodeCatchmentRadiusMeters}m
                    </em>
                    <br />
                    Mode: {node.mode}
                    <br />
                    Node ID: {node.nodeId}
                    <br />
                    Distance:{" "}
                    {Math.round(Number(node.distanceMeters || 0)).toLocaleString()}m
                    <br />
                    Boarding: {Number(node.boarding || 0).toLocaleString()}
                    <br />
                    Alighting: {Number(node.alighting || 0).toLocaleString()}
                    <br />
                    Total: {Number(node.total || 0).toLocaleString()}
                  </Popup>
                </CircleMarker>
              );
            })}

          {selectedPoint &&
            selectedPoint.source === "node-search" &&
            Number.isFinite(Number(selectedPoint.lat)) &&
            Number.isFinite(Number(selectedPoint.lng)) && (
              <CircleMarker
                key={`search-selected-${selectedPoint.nodeId}`}
                center={[Number(selectedPoint.lat), Number(selectedPoint.lng)]}
                radius={10}
                pathOptions={{
                  color: "#111111",
                  fillColor: "#ffcc00",
                  weight: 3,
                  opacity: 1,
                  fillOpacity: 0.9
                }}
              >
                <Popup>
                  <strong>{selectedPoint.nodeName}</strong>
                  <br />
                  <em style={{ color: "#666666" }}>Opened from node search</em>
                  <br />
                  Mode: {selectedPoint.mode}
                  <br />
                  Node ID: {selectedPoint.nodeId}
                </Popup>
              </CircleMarker>
            )}
        </MapContainer>
      </section>
    </div>
  );
}
