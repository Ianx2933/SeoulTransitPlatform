/**
 * Displays selected point, node-centered all-route demand, and radius catchment demand.
 */
export default function NodeDetailPanel({
  selectedPoint,
  nodeDetail,
  catchment,
  radiusMeters,
  onRadiusMetersChange,
  loading,
  catchmentLoading,
  errorMessage,
  onRetry,
  onClose
}) {
  if (!selectedPoint) {
    return null;
  }

  return (
    <section className="node-detail-panel" style={panelStyle}>
      <PanelHeader
        selectedPoint={selectedPoint}
        loading={loading}
        errorMessage={errorMessage}
        onRetry={onRetry}
        onClose={onClose}
      />

      <SelectedPointSection selectedPoint={selectedPoint} />

      {nodeDetail ? (
        <NodeAllRoutesSection nodeDetail={nodeDetail} />
      ) : !loading && !errorMessage ? (
        <section style={subSectionStyle}>
          <strong>This node, all routes</strong>
          <p style={mutedParagraphStyle}>
            No node-level demand is available for the current filters.
          </p>
        </section>
      ) : null}

      {catchment ? (
        <CatchmentSection
          catchment={catchment}
          radiusMeters={radiusMeters}
          onRadiusMetersChange={onRadiusMetersChange}
          catchmentLoading={catchmentLoading}
        />
      ) : !catchmentLoading && !errorMessage ? (
        <CatchmentEmptySection
          radiusMeters={radiusMeters}
          onRadiusMetersChange={onRadiusMetersChange}
        />
      ) : null}
    </section>
  );
}

/**
 * Renders the panel header and error state.
 */
function PanelHeader({ selectedPoint, loading, errorMessage, onRetry, onClose }) {
  return (
    <div>
      <div style={headerStyle}>
        <div>
          <strong>Selected node analysis</strong>
          <p style={mutedParagraphStyle}>
            {selectedPoint.nodeName || "Unknown node"} / {selectedPoint.nodeId || "Unknown ID"}
          </p>
        </div>

        <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
          {errorMessage && (
            <button type="button" onClick={onRetry}>Retry</button>
          )}
          <button type="button" onClick={onClose}>Close</button>
        </div>
      </div>

      {loading && <p style={mutedParagraphStyle}>Loading node-based demand...</p>}
      {errorMessage && (
        <p style={{ ...mutedParagraphStyle, color: "#b00020", whiteSpace: "pre-line" }}>
          {errorMessage}
        </p>
      )}
    </div>
  );
}

/**
 * Shows the clicked marker's route-selected point value.
 */
function SelectedPointSection({ selectedPoint }) {
  const boarding = Number(selectedPoint.boarding || 0);
  const alighting = Number(selectedPoint.alighting || 0);
  const total = boarding + alighting;

  return (
    <section style={subSectionStyle}>
      <strong>Selected point</strong>
      <p style={mutedParagraphStyle}>
        This is the clicked marker value from the currently loaded route layer.
      </p>
      <dl style={definitionGridStyle}>
        <dt>Mode</dt><dd style={definitionValueStyle}>{selectedPoint.mode || "Unknown"}</dd>
        <dt>Route</dt><dd style={definitionValueStyle}>{selectedPoint.serviceId || "Unknown"}</dd>
        <dt>Node ID</dt><dd style={definitionValueStyle}>{selectedPoint.nodeId || "Unknown"}</dd>
        <dt>Boarding</dt><dd style={definitionValueStyle}>{boarding.toLocaleString()}</dd>
        <dt>Alighting</dt><dd style={definitionValueStyle}>{alighting.toLocaleString()}</dd>
        <dt>Total</dt><dd style={definitionValueStyle}>{total.toLocaleString()}</dd>
        <dt>Latitude</dt><dd style={definitionValueStyle}>{formatCoordinate(selectedPoint.lat)}</dd>
        <dt>Longitude</dt><dd style={definitionValueStyle}>{formatCoordinate(selectedPoint.lng)}</dd>
      </dl>
    </section>
  );
}

/**
 * Shows all-route demand for the selected node.
 */
function NodeAllRoutesSection({ nodeDetail }) {
  const routeCount = nodeDetail.routes?.length || 0;

  return (
    <section style={subSectionStyle}>
      <strong>This node, all routes ({routeCount} route{routeCount === 1 ? "" : "s"})</strong>
      <dl style={definitionGridStyle}>
        <dt>Boarding</dt><dd style={definitionValueStyle}>{Number(nodeDetail.boarding || 0).toLocaleString()}</dd>
        <dt>Alighting</dt><dd style={definitionValueStyle}>{Number(nodeDetail.alighting || 0).toLocaleString()}</dd>
        <dt>Total</dt><dd style={definitionValueStyle}>{Number(nodeDetail.total || 0).toLocaleString()}</dd>
      </dl>
      <RouteList routes={nodeDetail.routes || []} />
    </section>
  );
}

/**
 * Shows the catchment radius toggle when no catchment data is available.
 *
 * The toggle stays interactive so the user can switch radius and refire the API.
 */
function CatchmentEmptySection({ radiusMeters, onRadiusMetersChange }) {
  return (
    <section style={subSectionStyle}>
      <div style={{ display: "flex", gap: "12px", alignItems: "center", flexWrap: "wrap" }}>
        <strong>Nearby catchment</strong>
        <fieldset style={{ display: "flex", gap: "8px", alignItems: "center", border: 0, padding: 0, margin: 0 }}>
          <legend style={visuallyHiddenStyle}>Catchment radius</legend>
          {[400, 800, 1000].map((radius) => (
            <label key={radius} style={{ display: "flex", gap: "4px", alignItems: "center" }}>
              <input
                type="radio"
                name="node-catchment-radius"
                value={radius}
                checked={radiusMeters === radius}
                onChange={() => onRadiusMetersChange(radius)}
              />
              {radius.toLocaleString()}m
            </label>
          ))}
        </fieldset>
      </div>
      <p style={mutedParagraphStyle}>
        No catchment demand is available for the current filters.
      </p>
    </section>
  );
}

/**
 * Shows radius-based catchment demand around the selected node.
 */
function CatchmentSection({ catchment, radiusMeters, onRadiusMetersChange, catchmentLoading }) {
  return (
    <section style={subSectionStyle}>
      <div style={{ display: "flex", gap: "12px", alignItems: "center", flexWrap: "wrap" }}>
        <strong>Nearby catchment</strong>
        <fieldset style={{ display: "flex", gap: "8px", alignItems: "center", border: 0, padding: 0, margin: 0 }}>
          <legend style={visuallyHiddenStyle}>Catchment radius</legend>
          {[400, 800, 1000].map((radius) => (
            <label key={radius} style={{ display: "flex", gap: "4px", alignItems: "center" }}>
              <input
                type="radio"
                name="node-catchment-radius"
                value={radius}
                checked={radiusMeters === radius}
                onChange={() => onRadiusMetersChange(radius)}
              />
              {radius.toLocaleString()}m
            </label>
          ))}
        </fieldset>
        {catchmentLoading && <span>Updating catchment...</span>}
      </div>

      <dl style={definitionGridStyle}>
        <dt>Nodes</dt><dd style={definitionValueStyle}>{(catchment.nodes || []).length.toLocaleString()}</dd>
        <dt>Routes</dt><dd style={definitionValueStyle}>{(catchment.routes || []).length.toLocaleString()}</dd>
        <dt>Boarding</dt><dd style={definitionValueStyle}>{Number(catchment.boarding || 0).toLocaleString()}</dd>
        <dt>Alighting</dt><dd style={definitionValueStyle}>{Number(catchment.alighting || 0).toLocaleString()}</dd>
        <dt>Total</dt><dd style={definitionValueStyle}>{Number(catchment.total || 0).toLocaleString()}</dd>
      </dl>

      <NodeList nodes={catchment.nodes || []} />
      <RouteList routes={catchment.routes || []} title="Routes in radius" />
    </section>
  );
}

/**
 * Renders nearby nodes ordered by distance.
 */
function NodeList({ nodes }) {
  if (nodes.length === 0) {
    return <p style={mutedParagraphStyle}>No nearby nodes found for the selected radius.</p>;
  }

  return (
    <div style={{ marginTop: "10px" }}>
      <strong>Nearby nodes</strong>
      <ol style={scrollListStyle}>
        {nodes.slice(0, 40).map((node) => (
          <li key={`${node.mode}-${node.nodeId}-${node.distanceMeters}`}>
            [{node.mode}] {node.nodeName} — {Math.round(Number(node.distanceMeters || 0)).toLocaleString()}m — {Number(node.total || 0).toLocaleString()}
          </li>
        ))}
      </ol>
    </div>
  );
}

/**
 * Renders route-level demand breakdown.
 */
function RouteList({ routes, title = "Route breakdown" }) {
  if (routes.length === 0) {
    return <p style={mutedParagraphStyle}>No route demand is available for this selection.</p>;
  }

  return (
    <div style={{ marginTop: "10px" }}>
      <strong>{title}</strong>
      <ol style={scrollListStyle}>
        {routes.slice(0, 40).map((route) => (
          <li key={`${route.mode}-${route.serviceId}`}>
            [{route.mode}] {route.serviceId} — {Number(route.total || 0).toLocaleString()} (board {Number(route.boarding || 0).toLocaleString()} / alight {Number(route.alighting || 0).toLocaleString()})
          </li>
        ))}
      </ol>
    </div>
  );
}

/**
 * Formats map coordinates for display.
 */
function formatCoordinate(value) {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue.toFixed(6) : "Unknown";
}

const panelStyle = {
  margin: "12px 16px",
  padding: "12px 14px",
  border: "1px solid #dddddd",
  borderRadius: "10px",
  backgroundColor: "#ffffff",
  fontSize: "14px",
  lineHeight: 1.5
};

const headerStyle = {
  display: "flex",
  gap: "12px",
  justifyContent: "space-between",
  alignItems: "flex-start",
  flexWrap: "wrap"
};

const subSectionStyle = {
  marginTop: "14px",
  paddingTop: "12px",
  borderTop: "1px solid #eeeeee"
};

const definitionGridStyle = {
  display: "grid",
  gridTemplateColumns: "max-content 1fr",
  gap: "6px 12px",
  margin: "10px 0 0"
};

const definitionValueStyle = { margin: 0 };

const mutedParagraphStyle = {
  margin: "6px 0 0",
  color: "#555555"
};

const scrollListStyle = {
  maxHeight: "180px",
  overflowY: "auto",
  margin: "6px 0 0",
  paddingRight: "12px"
};

const visuallyHiddenStyle = {
  position: "absolute",
  width: "1px",
  height: "1px",
  overflow: "hidden"
};
