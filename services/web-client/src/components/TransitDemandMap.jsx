import { useEffect, useMemo, useState } from "react";
import { CircleMarker, MapContainer, Popup, TileLayer } from "react-leaflet";
import { fetchMapDemand } from "../api/mapDemandApi.js";

/**
 * Seoul center coordinate used as the initial map view.
 */
const SEOUL_CENTER = [37.5665, 126.9780];

/**
 * Converts demand volume into a readable circle radius.
 */
function calculateRadius(point, metric) {
  const demand = getDemandValue(point, metric);

  if (demand <= 0) {
    return 3;
  }

  return Math.min(24, Math.max(4, Math.sqrt(demand) / 4));
}

/**
 * Returns demand value by metric.
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

/**
 * Main map component for visualizing transit demand.
 *
 * The map does not load data on initial render.
 * It only loads data when applied filters are provided by the parent component.
 */
export default function TransitDemandMap({ filters }) {
  const [points, setPoints] = useState([]);
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");

  const metric = filters?.metric || "total";

  /**
   * Sort high-demand points first so important markers are easier to inspect.
   */
  const sortedPoints = useMemo(() => {
    return [...points].sort(
      (a, b) => getDemandValue(b, metric) - getDemandValue(a, metric)
    );
  }, [points, metric]);

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
        const data = await fetchMapDemand({
          mode: filters.mode,
          line: filters.line,
          dayType: filters.dayType,
          hour: filters.hour
        });

        if (!ignore) {
          setPoints(data);
        }
      } catch (error) {
        if (!ignore) {
          setErrorMessage(error.message);
          setPoints([]);
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
          padding: "8px 16px",
          fontSize: "14px"
        }}
      >
        {!filters && "Select filters and click Load demand."}
        {filters && loading && "Loading..."}
        {filters && !loading && `${points.length.toLocaleString()} points loaded`}
      </div>

      <section className="map-card">
        <MapContainer
          center={SEOUL_CENTER}
          zoom={11}
          scrollWheelZoom
          className="leaflet-map"
        >
          <TileLayer
            attribution='&copy; OpenStreetMap contributors'
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          />

          {sortedPoints.map((point) => {
            const key =
              `${point.mode}-${point.serviceId}-${point.nodeId}-${point.nodeName}-${point.lat}-${point.lng}`;

            const radius = calculateRadius(point, metric);

            return (
              <CircleMarker
                key={key}
                center={[point.lat, point.lng]}
                radius={radius}
                pathOptions={{
                  weight: 1,
                  opacity: 0.75,
                  fillOpacity: 0.45
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
                    (Number(point.boarding || 0) + Number(point.alighting || 0))
                      .toLocaleString()
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
