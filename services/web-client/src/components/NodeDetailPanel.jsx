/**
 * Displays details for the currently selected stop or station.
 *
 * This panel uses the point data that is already loaded on the map.
 */
export default function NodeDetailPanel({
  node,
  metric,
  radiusMeters,
  onRadiusMetersChange,
  nearbyNodes,
  onClose
}) {
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

  const nearbySummary = summarizeNearbyNodes(nearbyNodes);

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

      <section
        style={{
          marginTop: "14px",
          paddingTop: "12px",
          borderTop: "1px solid #eeeeee"
        }}
      >
        <div
          style={{
            display: "flex",
            gap: "12px",
            alignItems: "center",
            flexWrap: "wrap"
          }}
        >
          <strong>Nearby node grouping</strong>

          <label>
            Radius
            <select
              value={radiusMeters}
              onChange={(event) =>
                onRadiusMetersChange(Number(event.target.value))
              }
              style={{
                marginLeft: "8px"
              }}
            >
              <option value={300}>300m</option>
              <option value={500}>500m</option>
              <option value={800}>800m</option>
            </select>
          </label>
        </div>

        <p
          style={{
            margin: "8px 0 0",
            color: "#555555"
          }}
        >
          {nearbySummary.count.toLocaleString()} loaded node(s) within{" "}
          {radiusMeters.toLocaleString()}m.
        </p>

        <dl
          style={{
            display: "grid",
            gridTemplateColumns: "max-content 1fr",
            gap: "6px 12px",
            margin: "10px 0 0"
          }}
        >
          <dt>Nearby boarding</dt>
          <dd style={{ margin: 0 }}>
            {nearbySummary.boarding.toLocaleString()}
          </dd>

          <dt>Nearby alighting</dt>
          <dd style={{ margin: 0 }}>
            {nearbySummary.alighting.toLocaleString()}
          </dd>

          <dt>Nearby total</dt>
          <dd style={{ margin: 0 }}>
            {nearbySummary.total.toLocaleString()}
          </dd>
        </dl>

        {nearbyNodes.length > 0 && (
          <ol
            style={{
              maxHeight: "180px",
              overflowY: "auto",
              margin: "10px 0 0",
              paddingRight: "12px"
            }}
          >
            {nearbyNodes.slice(0, 30).map((nearbyNode) => (
              <li key={nearbyNode.groupingKey}>
                [{nearbyNode.mode}] {nearbyNode.serviceId} /{" "}
                {nearbyNode.nodeName} —{" "}
                {Math.round(nearbyNode.distanceMeters).toLocaleString()}m,{" "}
                {(
                  Number(nearbyNode.boarding || 0) +
                  Number(nearbyNode.alighting || 0)
                ).toLocaleString()}
              </li>
            ))}
          </ol>
        )}
      </section>
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
 * Summarizes nearby nodes within the selected radius.
 */
function summarizeNearbyNodes(nearbyNodes) {
  return nearbyNodes.reduce(
    (accumulator, node) => {
      const boarding = Number(node.boarding || 0);
      const alighting = Number(node.alighting || 0);

      accumulator.count += 1;
      accumulator.boarding += boarding;
      accumulator.alighting += alighting;
      accumulator.total += boarding + alighting;

      return accumulator;
    },
    {
      count: 0,
      boarding: 0,
      alighting: 0,
      total: 0
    }
  );
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
