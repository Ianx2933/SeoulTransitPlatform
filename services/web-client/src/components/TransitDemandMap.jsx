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

import { fetchMapDemand } from "../api/mapDemandApi.js";

const SEOUL_CENTER = [37.5665, 126.9780];

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
 * Leaflet-compatible basemap tile layers.
 *
 * Kakao Map is not included because it uses a separate JavaScript SDK rather
 * than a simple Leaflet TileLayer URL.
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
function getRouteColor(serviceId) {
  const text = String(serviceId || "");
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
 * Creates a demand summary for the currently visible points.
 *
 * This summary is calculated on the client for fast MVP iteration.
 * It can later move to PostGIS/materialized views when the dataset grows.
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
    const key = point.serviceId || "unknown";
    const existing = routeMap.get(key) || {
      serviceId: key,
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
      setErrorMessage("");
      return;
    }

    let ignore = false;

    async function loadDemand() {
      setLoading(true);
      setErrorMessage("");

      try {
        const data = await fetchMapDemand({
          mode: filters.mode,
          lines: filters.lines,
          dayTypes: filters.dayTypes,
          dayAggregation: filters.dayAggregation,
          hours: filters.hours
        });

        if (!ignore) {
          setPoints(data);
          setSelectedDistricts([]);
        }
      } catch (error) {
        if (!ignore) {
          setErrorMessage(error.message);
          setPoints([]);
          setSelectedDistricts([]);
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
              onClick={() => setSelectedDistricts([])}
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
                <li key={route.serviceId}>
                  <span
                    style={{
                      display: "inline-block",
                      width: "10px",
                      height: "10px",
                      borderRadius: "50%",
                      marginRight: "6px",
                      backgroundColor: getRouteColor(route.serviceId)
                    }}
                  />
                  {route.serviceId}
                  {" — "}
                  {Number(route[metric] || 0).toLocaleString()}
                </li>
              ))}
            </ol>
          </div>
        </section>
      )}

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
            const color = getRouteColor(point.serviceId);
            const key =
              `${point.mode}-${point.serviceId}-${point.nodeId}-${point.nodeName}-${point.lat}-${point.lng}`;

            const radius = calculateRadius(point, metric);

            return (
              <CircleMarker
                key={key}
                center={[point.lat, point.lng]}
                radius={radius}
                pathOptions={{
                  color,
                  fillColor: color,
                  weight: 1,
                  opacity: 0.85,
                  fillOpacity: 0.5
                }}
              >
                <Popup>
                  <strong>{point.nodeName}</strong>
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
