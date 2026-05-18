/**
 * Displays details for the currently selected stop or station.
 *
 * This panel uses the point data that is already loaded on the map.
 */
export default function NodeDetailPanel({ node, metric, onClose }) {
  if (!node) {
    return null;
  }

  const boarding = Number(node.boarding || 0);
  const alighting = Number(node.alighting || 0);
  const total = boarding + alighting;
  const selectedMetricValue = getMetricValue({
    boarding,
    alighting,
    total,
    metric
  });

  return (
    <section
      className="node-detail-panel"
      style={{
        margin: "12px 16px",
        padding: "12px 14px",
        border: "1px solid #dddddd",
        borderRadius: "10px",
        backgroundColor: "#ffffff",
        fontSize: "14px",
        lineHeight: 1.5
      }}
    >
      <div
        style={{
          display: "flex",
          gap: "12px",
          justifyContent: "space-between",
          alignItems: "flex-start",
          flexWrap: "wrap"
        }}
      >
        <div>
          <strong>Selected node detail</strong>
          <p
            style={{
              margin: "4px 0 0",
              color: "#555555"
            }}
          >
            [{node.mode || "unknown"}] {node.serviceId || "unknown"}
          </p>
        </div>

        <button type="button" onClick={onClose}>
          Close
        </button>
      </div>

      <dl
        style={{
          display: "grid",
          gridTemplateColumns: "max-content 1fr",
          gap: "6px 12px",
          margin: "12px 0 0"
        }}
      >
        <dt>Node name</dt>
        <dd style={{ margin: 0 }}>{node.nodeName || "Unknown"}</dd>

        <dt>Node ID</dt>
        <dd style={{ margin: 0 }}>{node.nodeId || "Unknown"}</dd>

        <dt>Mode</dt>
        <dd style={{ margin: 0 }}>{node.mode || "Unknown"}</dd>

        <dt>Route</dt>
        <dd style={{ margin: 0 }}>{node.serviceId || "Unknown"}</dd>

        <dt>Boarding</dt>
        <dd style={{ margin: 0 }}>{boarding.toLocaleString()}</dd>

        <dt>Alighting</dt>
        <dd style={{ margin: 0 }}>{alighting.toLocaleString()}</dd>

        <dt>Total</dt>
        <dd style={{ margin: 0 }}>{total.toLocaleString()}</dd>

        <dt>Selected metric</dt>
        <dd style={{ margin: 0 }}>{selectedMetricValue.toLocaleString()}</dd>

        <dt>Latitude</dt>
        <dd style={{ margin: 0 }}>{formatCoordinate(node.lat)}</dd>

        <dt>Longitude</dt>
        <dd style={{ margin: 0 }}>{formatCoordinate(node.lng)}</dd>
      </dl>
    </section>
  );
}

/**
 * Returns the value for the active demand metric.
 */
function getMetricValue({ boarding, alighting, total, metric }) {
  if (metric === "boarding") {
    return boarding;
  }

  if (metric === "alighting") {
    return alighting;
  }

  return total;
}

/**
 * Formats map coordinates for display.
 */
function formatCoordinate(value) {
  const numberValue = Number(value);

  if (!Number.isFinite(numberValue)) {
    return "Unknown";
  }

  return numberValue.toFixed(6);
}
