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

import { fetchMultiModeMapDemand } from "../api/mapDemandApi.js";
import NodeDetailPanel from "./NodeDetailPanel.jsx";

const SEOUL_CENTER = [37.5665, 126.9780];
const EARTH_RADIUS_METERS = 6371000;

const ROUTE_COLOR_PALETTE = [
  "#1f77b4",
  "#ff7f0e",
  "#2ca02c",
  "#d62728",
  "#9467bd",
  "#8c564b",
  "#e377c2",
  "#7f7f7f",
  "#bcbd22",
  "#17becf"
];

/**
 * Basemap tile layers compatible with leaflet.
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
 *
 * The hash-based color assignment keeps the same route visually consistent
 * across map refreshes without storing colors in the database.
 */
function getRouteColor(mode, serviceId) {
  const text = `${mode || "unknown"}-${serviceId || ""}`;
  let hash = 0;

  for (let index = 0; index < text.length; index += 1) {
    hash = text.charCodeAt(index) + ((hash << 5) - hash);
  }

  const paletteIndex = Math.abs(hash) % ROUTE_COLOR_PALETTE.length;
  return ROUTE_COLOR_PALETTE[paletteIndex];
}

/**
 * Converts demand volume into a readable circle radius.
 *
 * Circle size represents demand intensity. The scale is intentionally softened
 * because central stations and major bus corridors can otherwise dominate the map.
 */
function calculateRadius(point, metric) {
  const demand = getDemandValue(point, metric);

  if (demand <= 0) {
    return 3;
  }

  return Math.min(
    24,
    Math.max(
      4,
      Math.sqrt(demand) / 5
    )
  );
}

/**
 * Returns demand value by selected metric.
 */
function getDemandValue(point, metric) {
  const boarding = Number(point.boarding || 0);
  const alighting = Number(point.alighting || 0);

  if (metric === "boarding") {
    return boarding;
  }

  if (metric === "alighting") {
    return alighting;
  }

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
  return (
    feature?.properties?.ADM_CD ||
    feature?.properties?.adm_cd ||
    ""
  );
}

function isValidPoint(point) {
  return (
    Number.isFinite(Number(point.lat)) &&
    Number.isFinite(Number(point.lng))
  );
}


/**
 * Creates a stable key for a loaded demand point.
 */
function createPointKey(point) {
  return [
    point.mode || "unknown",
    point.serviceId || "unknown",
    point.nodeId || "unknown",
    point.nodeName || "unknown",
    point.lat || "unknown",
    point.lng || "unknown"
  ].join("-");
}

/**
 * Calculates distance between two coordinates with the haversine formula.
 */
function calculateDistanceMeters(firstPoint, secondPoint) {
  if (!isValidPoint(firstPoint) || !isValidPoint(secondPoint)) {
    return Number.POSITIVE_INFINITY;
  }

  const firstLat = toRadians(Number(firstPoint.lat));
  const secondLat = toRadians(Number(secondPoint.lat));
  const deltaLat = toRadians(Number(secondPoint.lat) - Number(firstPoint.lat));
  const deltaLng = toRadians(Number(secondPoint.lng) - Number(firstPoint.lng));

  const haversine =
    Math.sin(deltaLat / 2) ** 2 +
    Math.cos(firstLat) *
      Math.cos(secondLat) *
      Math.sin(deltaLng / 2) ** 2;

  return EARTH_RADIUS_METERS * 2 * Math.atan2(
    Math.sqrt(haversine),
    Math.sqrt(1 - haversine)
  );
}

/**
 * Converts degrees to radians.
 * (각도를 라디안으로 변환합니다.)
 */
function toRadians(value) {
  return (value * Math.PI) / 180;
}

/**
 * Creates a demand summary for the currently visible points.
 *
 * Route aggregation uses mode + serviceId so bus and subway results can be shown together safely.
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

  const routes = Array.from(routeMap.values())
    .sort((a, b) => Number(b[metric] || 0) - Number(a[metric] || 0));

  return {
    totals,
    routes
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

  /**
   * Stores the stop or station selected from the map.
   */
  const [selectedNode, setSelectedNode] = useState(null);

  /**
   * Stores the radius used for nearby node grouping.
   */
  const [nodeGroupingRadiusMeters, setNodeGroupingRadiusMeters] =
    useState(500);

  const [loading, setLoading] = useState(false);
  const [geoLoading, setGeoLoading] = useState(true);

  const [errorMessage, setErrorMessage] = useState("");

  const metric = filters?.metric || "total";
  const tileLayer = TILE_LAYERS[selectedTileLayer] || TILE_LAYERS.cartoLight;

  const sortedPoints = useMemo(() => {
    return [...points].sort(
      (a, b) => getDemandValue(b, metric) - getDemandValue(a, metric)
    );
  }, [points, metric]);

  /**
   * Filters points by selected administrative districts.
   *
   * If no district is selected, the full loaded demand layer remains visible.
   *
   * If one or more districts are selected, a point is visible when it is inside at least one selected polygon.
   */
  const visiblePoints = useMemo(() => {
    if (selectedDistricts.length === 0) {
      return sortedPoints;
    }

    return sortedPoints.filter((transitPoint) => {
      if (!isValidPoint(transitPoint)) {
        return false;
      }

      const candidatePoint = turfPoint([
        Number(transitPoint.lng),
        Number(transitPoint.lat)
      ]);

      return selectedDistricts.some((district) =>
        booleanPointInPolygon(candidatePoint, district)
      );
    });
  }, [sortedPoints, selectedDistricts]);


  /**
   * Finds visible points within the selected radius from the selected node.
   */
  const nearbyNodes = useMemo(() => {
    if (!selectedNode || !isValidPoint(selectedNode)) {
      return [];
    }

    return visiblePoints
      .filter(isValidPoint)
      .map((point) => ({
        ...point,
        groupingKey: createPointKey(point),
        distanceMeters: calculateDistanceMeters(selectedNode, point)
      }))
      .filter((point) => point.distanceMeters <= nodeGroupingRadiusMeters)
      .sort((a, b) => a.distanceMeters - b.distanceMeters);
  }, [selectedNode, visiblePoints, nodeGroupingRadiusMeters]);

  const nearbyNodeKeys = useMemo(() => {
    return new Set(nearbyNodes.map((point) => point.groupingKey));
  }, [nearbyNodes]);

  const selectedNodeKey = selectedNode ? createPointKey(selectedNode) : "";

  const demandSummary = useMemo(() => {
    return summarizeDemand(visiblePoints, metric);
  }, [visiblePoints, metric]);

  const selectedDistrictCodes = useMemo(() => {
    return new Set(selectedDistricts.map(getDistrictCode));
  }, [selectedDistricts]);

  useEffect(() => {
    let ignore = false;

    async function loadAdminDongGeoJson() {
      setGeoLoading(true);

      try {
        const response = await fetch(
          "/data/capital_area_admin_dong_4326.geojson"
        );

        if (!response.ok) {
          throw new Error(
            `Failed to load GeoJSON: ${response.status}`
          );
        }

        const data = await response.json();

        if (!ignore) {
          setAdminDongGeoJson(data);
        }
      } catch (error) {
        if (!ignore) {
          setErrorMessage(error.message);
        }
      } finally {
        if (!ignore) {
          setGeoLoading(false);
        }
      }
    }

    loadAdminDongGeoJson();

    return () => {
      ignore = true;
    };
  }, []);

  useEffect(() => {
    if (!filters) {
      setPoints([]);
      setSelectedDistricts([]);
      setSelectedNode(null);
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
          setSelectedNode(null);
        }
      } catch (error) {
        if (!ignore) {
          setErrorMessage(error.message);
          setPoints([]);
          setSelectedDistricts([]);
          setSelectedNode(null);
        }
      } finally {
        if (!ignore) {
          setLoading(false);
        }
      }
    }

    loadDemand();

    return () => {
      ignore = true;
    };
  }, [filters]);

  /**
   * Handles district selection.
   *
   * Click selects one district.
   *
   * Ctrl/Shift/Meta click toggles districts to support multi-district analysis.
   */
  const toggleDistrictSelection = (feature, event) => {
    const code = getDistrictCode(feature);
    const multiSelect =
      event.originalEvent.ctrlKey ||
      event.originalEvent.shiftKey ||
      event.originalEvent.metaKey;

    if (!multiSelect) {
      setSelectedDistricts([feature]);
      setSelectedNode(null);
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

    setSelectedNode(null);
  };

  const handleEachDistrict = (feature, layer) => {
    layer.on({
      click: (event) => {
        toggleDistrictSelection(feature, event);
      }
    });

    layer.bindTooltip(getDistrictName(feature), {
      sticky: true
    });
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
        <div className="error-box">
          API error: {errorMessage}
        </div>
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
          {!geoLoading && !filters && "Select filters and click Load demand."}
          {filters && loading && "Loading demand data..."}
          {filters && !loading &&
            `${visiblePoints.length.toLocaleString()} / ${points.length.toLocaleString()} points shown`}
        </span>

        {selectedDistricts.length > 0 && (
          <>
            <strong>
              Districts: {selectedDistricts.map(getDistrictName).join(", ")}
            </strong>

            <button
              type="button"
              onClick={() => {
                setSelectedDistricts([]);
                setSelectedNode(null);
              }}
            >
              Clear districts
            </button>
          </>
        )}
      </div>

      {filters && !loading && (
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
            {filters?.dayAggregation === "sum"
              ? " — sum of selected days"
              : " — average per selected day"}
          </strong>
          <div>
            Boarding: {demandSummary.totals.boarding.toLocaleString()}
            {" | "}
            Alighting: {demandSummary.totals.alighting.toLocaleString()}
            {" | "}
            Total: {demandSummary.totals.total.toLocaleString()}
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
                  [{route.mode}] {route.serviceId}
                  {" — "}
                  {Number(route[metric] || 0).toLocaleString()}
                </li>
              ))}
            </ol>
          </div>
        </section>
      )}

      <NodeDetailPanel
        node={selectedNode}
        metric={metric}
        radiusMeters={nodeGroupingRadiusMeters}
        onRadiusMetersChange={setNodeGroupingRadiusMeters}
        nearbyNodes={nearbyNodes}
        onClose={() => setSelectedNode(null)}
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
            const isSelectedNode = key === selectedNodeKey;
            const isNearbyNode = nearbyNodeKeys.has(key);

            const radius = calculateRadius(point, metric);

            return (
              <CircleMarker
                key={key}
                center={[point.lat, point.lng]}
                radius={
                  isSelectedNode
                    ? radius + 4
                    : isNearbyNode
                      ? radius + 2
                      : radius
                }
                pathOptions={{
                  color,
                  fillColor: color,
                  weight: isSelectedNode ? 4 : isNearbyNode ? 2 : 1,
                  opacity: isSelectedNode || isNearbyNode ? 1 : 0.85,
                  fillOpacity: isSelectedNode ? 0.9 : isNearbyNode ? 0.7 : 0.5
                }}
                eventHandlers={{
                  click: () => setSelectedNode(point)
                }}
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
                  Total: {
                    (
                      Number(point.boarding || 0) +
                      Number(point.alighting || 0)
                    ).toLocaleString()
                  }
                </Popup>
              </CircleMarker>
            );
          })}
        </MapContainer>
      </section>
    </div>
  );
}
