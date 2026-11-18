import { useI18n } from "../i18n/I18nProvider.jsx";
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
import { fetchDongOdSummary } from "../api/dongOdApi.js";
import { busRouteStyle } from "../utils/busRouteType.js";
import { colorOf, DEMAND_COLORS, dongDayType, quantileBreaks } from "../utils/dongColor.js";
import DongOdPanel from "./DongOdPanel.jsx";
import DistrictDemandPanel from "./DistrictDemandPanel.jsx";
import NodeDetailPanel from "./NodeDetailPanel.jsx";
import RoadExploreCard from "./RoadExploreCard.jsx";
import { RoadLegendOverlay, RoadMapLayers } from "./RoadMapLayers.jsx";
import { useRoadLayer } from "../hooks/useRoadLayer.js";

const SEOUL_CENTER = [37.5665, 126.9780];

const ROUTE_COLOR_PALETTE = [
  "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
  "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"
];

/**
 * Basemap options.
 *
 * The CartoDB Positron and Dark Matter layers were removed: CARTO's basemap
 * CDN no longer serves unauthenticated requests and returns an "API KEY
 * REQUIRED" watermark tile instead. Restoring them means signing up for a
 * CARTO key, which would then be embedded in this client bundle.
 *
 * OpenStreetMap's attribution is required by its licence, so it stays in the
 * rendered attribution control.
 */
const TILE_LAYERS = {
  osm: {
    label: "OpenStreetMap",
    url: "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    attribution:
      '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }
};

/**
 * Returns a stable color for each route.
 */
function getRouteColor(mode, serviceId) {
  // Bus and Han River bus routes use Seoul's colour by route type.
  if (mode === "bus" || mode === "ferry") {
    return busRouteStyle(serviceId, mode).bg;
  }
  // Bus routes take the colour of their Seoul route type (trunk, branch, ...).
  if (mode === "bus") {
    return busRouteStyle(serviceId).background;
  }
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

function getDistrictName(feature, tx = (text) => text) {
  return (
    feature?.properties?.ADM_NM ||
    feature?.properties?.adm_nm ||
    tx("Selected district")
  );
}

const METRIC_NAMES = { total: "boarding + alighting", boarding: "boarding", alighting: "alighting" };

/** Mode name in the interface language, or the raw mode. */
function modeName(tx, mode) {
  const names = { subway: "Subway", bus: "Bus", ferry: "Han River bus" };
  return names[mode] ? tx(names[mode]) : mode || tx("Unknown");
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
  selectedTileLayer = "osm",
  selectedSearchNode,
  onClearSearchNode,
  selectedSubwayLines = [],
  selectedBusLines = [],
  onAddRouteFromNode,
  showCatchmentMarkers = true,
  onShowCatchmentMarkersChange
}) {
  const { tx } = useI18n();
  const [points, setPoints] = useState([]);
  const [adminDongGeoJson, setAdminDongGeoJson] = useState(null);
  const [selectedDistricts, setSelectedDistricts] = useState([]);
  // District demand colours (TOPIS district OD, low green to high red).
  const [dongColorOn, setDongColorOn] = useState(true);
  const [dongColorBy, setDongColorBy] = useState("departures");
  const [dongColorMode, setDongColorMode] = useState("all");
  const [dongSummary, setDongSummary] = useState(null);
  // Road layer: the district panel's road view and stop/segment look-ups.
  const road = useRoadLayer();
  const routeLoad = road.routeLoad;
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
  const tileLayer = TILE_LAYERS[selectedTileLayer] || TILE_LAYERS.osm;

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

  const dongDay = dongDayType(filters?.dayTypes);

  useEffect(() => {
    if (!showAdminBoundary || !dongColorOn) return undefined;
    let ignore = false;
    fetchDongOdSummary({ dayType: dongDay })
      .then((rows) => {
        if (!ignore) setDongSummary({ dayType: dongDay, rows });
      })
      .catch(() => {
        if (!ignore) setDongSummary(null);
      });
    return () => {
      ignore = true;
    };
  }, [showAdminBoundary, dongColorOn, dongDay]);

  const dongValues = useMemo(() => {
    const m = new Map();
    // Field of the summary row: departures / arrivals, or by rail / bus.
    const field =
      dongColorMode === "all"
        ? dongColorBy
        : `${dongColorMode}${dongColorBy === "departures" ? "Departures" : "Arrivals"}`;
    for (const r of dongSummary?.rows || []) m.set(r.code, Number(r[field] || 0));
    return m;
  }, [dongSummary, dongColorBy, dongColorMode]);
  const dongBreaks = useMemo(() => quantileBreaks([...dongValues.values()]), [dongValues]);
  const dongColorKey =
    dongColorOn && dongSummary ? `${dongSummary.dayType}-${dongColorBy}-${dongColorMode}-${dongValues.size}` : "plain";
  const lastDistrict = selectedDistricts.length > 0 ? selectedDistricts[selectedDistricts.length - 1] : null;

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
    const code = getDistrictCode(feature);
    const isSelected = selectedDistrictCodes.has(code);
    const fill = dongColorOn ? colorOf(dongValues.get(code), dongBreaks) : null;
    return {
      color: isSelected ? "#111111" : "#2b2f36",
      weight: isSelected ? 3.5 : 1.3,
      opacity: isSelected ? 1 : 0.75,
      fillColor: fill || "#3388ff",
      // Fainter while the road layer is on, so the lines stay readable.
      fillOpacity: (fill ? (isSelected ? 0.6 : 0.45) : isSelected ? 0.14 : 0.04) * (routeLoad ? 0.35 : 1)
    };
  };

  const pickDistrictByCode = (code) => {
    const feature = adminDongGeoJson?.features?.find((f) => getDistrictCode(f) === code);
    if (feature) setSelectedDistricts([feature]);
  };

  return (
    <div className="map-page">
      {errorMessage && (
        <div className="error-box" role="alert">{tx("API error: {message}", { message: errorMessage })}</div>
      )}

      <div
        className="map-status-bar"
      >
        <span>
          {geoLoading && tx("Loading district boundaries…")}
          {!geoLoading &&
            !filters &&
            points.length === 0 &&
            tx("Pick filters and press Load demand, or search for a stop or station.")}
          {filters && loading && tx("Loading demand…")}
          {filters &&
            !loading &&
            points.length === 0 &&
            tx("No demand on the map. Stop analysis reads stop detail and catchment directly.")}
          {filters &&
            !loading &&
            points.length > 0 &&
            tx("{shown} / {all} points shown", { shown: visiblePoints.length.toLocaleString(), all: points.length.toLocaleString() })}
        </span>

        {filters?.autoApplied && (
          <span className="warn-pill">{tx("Filters auto-applied from current draft (no routes loaded). Adjust filters and click Load demand to update the map layer.")}</span>
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
            />{tx("Show catchment markers on map")}</label>
        )}

        {!showAdminBoundary && <span>{tx("District boundaries are hidden.")}</span>}

        {showAdminBoundary && (
          <span className="dong-legend">
            <label className="check">
              <input type="checkbox" checked={dongColorOn} onChange={(e) => setDongColorOn(e.target.checked)} />
              {tx("Colour districts by demand")}
            </label>
            {dongColorOn && (
              <>
                <select value={dongColorBy} onChange={(e) => setDongColorBy(e.target.value)} aria-label={tx("Colour by")}>
                  <option value="departures">{tx("Departures")}</option>
                  <option value="arrivals">{tx("Arrivals")}</option>
                </select>
                <select value={dongColorMode} onChange={(e) => setDongColorMode(e.target.value)} aria-label={tx("Mode")}>
                  <option value="all">{tx("Rail and bus")}</option>
                  <option value="subway">{tx("Rail")}</option>
                  <option value="bus">{tx("Bus")}</option>
                </select>
                <span className="dong-legend__scale" aria-hidden="true">
                  {tx("Low")}
                  {DEMAND_COLORS.map((c) => (
                    <i key={c} style={{ background: c }} />
                  ))}
                  {tx("High")}
                </span>
              </>
            )}
          </span>
        )}

        {showAdminBoundary && selectedDistricts.length > 0 && (
          <>
            <strong>
              {tx("Demand summary limited to: {names}", {
                names: selectedDistricts.map((d) => getDistrictName(d, tx)).join(", ")
              })}
            </strong>
            <button
              type="button"
              onClick={clearDistrictSelection}
            >{tx("Clear districts")}</button>
          </>
        )}
      </div>

      <div className="map-body">
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

          {showAdminBoundary && adminDongGeoJson && (!routeLoad || road.boundariesOn) && (
            <GeoJSON
              key={`${selectedDistricts.map(getDistrictCode).join("-") || "all"}-${dongColorKey}-${routeLoad ? "road" : ""}`}
              data={adminDongGeoJson}
              style={getDistrictStyle}
              onEachFeature={handleEachDistrict}
            />
          )}

          <RoadMapLayers road={road} boundariesShown={showAdminBoundary} />

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
                  {tx("Mode")}: {modeName(tx, point.mode)}
                  <br />
                  {tx("Route")}: {point.serviceId}
                  <br />
                  {tx("Stop ID")}: {point.nodeId}
                  <br />
                  {tx("Boarding")}: {Number(point.boarding || 0).toLocaleString()}
                  <br />
                  {tx("Alighting")}: {Number(point.alighting || 0).toLocaleString()}
                  <br />
                  {tx("Total")}:{" "}
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
                      {tx("Nearby stop, within {radius}m", { radius: nodeCatchmentRadiusMeters })}
                    </em>
                    <br />
                    {tx("Mode")}: {modeName(tx, node.mode)}
                    <br />
                    {tx("Stop ID")}: {node.nodeId}
                    <br />
                    {tx("Distance")}:{" "}
                    {Math.round(Number(node.distanceMeters || 0)).toLocaleString()}m
                    <br />
                    {tx("Boarding")}: {Number(node.boarding || 0).toLocaleString()}
                    <br />
                    {tx("Alighting")}: {Number(node.alighting || 0).toLocaleString()}
                    <br />
                    {tx("Total")}: {Number(node.total || 0).toLocaleString()}
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
                  <em style={{ color: "#666666" }}>{tx("Opened from stop search")}</em>
                  <br />
                  {tx("Mode")}: {modeName(tx, selectedPoint.mode)}
                  <br />
                  {tx("Stop ID")}: {selectedPoint.nodeId}
                </Popup>
              </CircleMarker>
            )}
        </MapContainer>
        <RoadLegendOverlay road={road} boundariesShown={showAdminBoundary} />
      </section>

      <aside className="map-side">
      <RoadExploreCard
        request={road.explore}
        state={road.exploreLoad.state}
        error={road.exploreLoad.error}
        result={road.exploreLoad.result}
        onClose={road.closeExplore}
      />
      {showAdminBoundary && lastDistrict && (
        <DongOdPanel
          code={getDistrictCode(lastDistrict)}
          name={getDistrictName(lastDistrict, tx)}
          picked={selectedDistrictSummaries}
          initialDayType={dongDay}
          onPickDistrict={pickDistrictByCode}
          onRouteLoad={road.setRouteLoad}
          boundariesOn={road.boundariesOn}
          onBoundariesChange={road.setBoundariesOn}
        />
      )}
      {filters && !loading && points.length > 0 && (
        <section className="demand-summary side-card">
          <strong>
            {tx("Demand summary")}
            {selectedDistricts.length > 0 ? ` — ${tx("chosen districts only")}` : ""}
            {filters?.dayAggregation === "sum"
              ? ` — ${tx("sum of chosen days")}`
              : ` — ${tx("mean per chosen day")}`}
          </strong>
          <div>
            {tx("Boarding {on} | Alighting {off} | Total {total}", {
              on: demandSummary.totals.boarding.toLocaleString(),
              off: demandSummary.totals.alighting.toLocaleString(),
              total: demandSummary.totals.total.toLocaleString()
            })}
          </div>

          <div style={{ marginTop: "8px" }}>
            <strong>{tx("Routes by {metric}", { metric: tx(METRIC_NAMES[metric] || metric) })}</strong>
            <span style={{ marginLeft: "8px", color: "#666666" }}>
              {tx("({count} routes)", { count: demandSummary.routes.length.toLocaleString() })}
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
                  {route.mode === "subway" ? tx("Subway") : route.mode === "bus" ? tx("Bus") : route.mode} {route.serviceId} —{" "}
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
        onRoadLookup={(ars, direction) => road.exploreStop(ars, direction, dongDay)}
      />

      </aside>
      </div>
    </div>
  );
}
