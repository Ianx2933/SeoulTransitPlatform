import { useMemo, useState } from "react";

import NodeAnalysisControls from "./NodeAnalysisControls.jsx";
import { sortCatchmentNodes, sortRoutes } from "../utils/nodeDetailSort.js";

/**
 * Displays selected point, all-route node demand, and catchment demand.
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
  onClose,
  sections,
  onToggleSection,
  selectedSubwayLines = [],
  selectedBusLines = [],
  onAddRouteFromNode
}) {
  const [nodeSortKey, setNodeSortKey] = useState("distanceAsc");
  const [routeSortKey, setRouteSortKey] = useState("totalDesc");

  const sortedNodeDetailRoutes = useMemo(
    () => sortRoutes(nodeDetail?.routes || [], routeSortKey),
    [nodeDetail, routeSortKey]
  );

  const sortedCatchmentNodes = useMemo(
    () => sortCatchmentNodes(catchment?.nodes || [], nodeSortKey),
    [catchment, nodeSortKey]
  );

  const sortedCatchmentRoutes = useMemo(
    () => sortRoutes(catchment?.routes || [], routeSortKey),
    [catchment, routeSortKey]
  );

  const shouldShowCatchmentArea =
    sections.showCatchment || sections.showNearbyNodes || sections.showRoutes;

  if (!selectedPoint) return null;

  return (
    <section className="node-detail-panel" style={panelStyle}>
      <PanelHeader
        selectedPoint={selectedPoint}
        loading={loading}
        errorMessage={errorMessage}
        onRetry={onRetry}
        onClose={onClose}
      />

      <NodeAnalysisControls
        sections={sections}
        onToggleSection={onToggleSection}
        nodeSortKey={nodeSortKey}
        onNodeSortKeyChange={setNodeSortKey}
        routeSortKey={routeSortKey}
        onRouteSortKeyChange={setRouteSortKey}
      />

      {sections.showSelectedPoint && (
        <SelectedPointSection selectedPoint={selectedPoint} />
      )}

      {sections.showNodeDetail &&
        (nodeDetail ? (
          <NodeAllRoutesSection
            nodeDetail={nodeDetail}
            routes={sortedNodeDetailRoutes}
            selectedSubwayLines={selectedSubwayLines}
            selectedBusLines={selectedBusLines}
            onAddRouteFromNode={onAddRouteFromNode}
          />
        ) : !loading && !errorMessage ? (
          <section style={subSectionStyle}>
            <strong>This node, all routes</strong>
            <p style={mutedParagraphStyle}>
              No node-level demand is available for the current filters.
            </p>
          </section>
        ) : null)}

      {shouldShowCatchmentArea &&
        (catchment ? (
          <CatchmentSection
            catchment={catchment}
            nodes={sortedCatchmentNodes}
            routes={sortedCatchmentRoutes}
            radiusMeters={radiusMeters}
            onRadiusMetersChange={onRadiusMetersChange}
            catchmentLoading={catchmentLoading}
            showCatchmentSummary={sections.showCatchment}
            showNearbyNodes={sections.showNearbyNodes}
            showRoutes={sections.showRoutes}
            selectedSubwayLines={selectedSubwayLines}
            selectedBusLines={selectedBusLines}
            onAddRouteFromNode={onAddRouteFromNode}
          />
        ) : !catchmentLoading && !errorMessage ? (
          <CatchmentEmptySection
            radiusMeters={radiusMeters}
            onRadiusMetersChange={onRadiusMetersChange}
          />
        ) : null)}
    </section>
  );
}

/**
 * Renders the panel header with source label, retry, and close controls.
 */
function PanelHeader({ selectedPoint, loading, errorMessage, onRetry, onClose }) {
  const sourceLabel =
    selectedPoint.source === "node-search" ? "Node search" : "Map marker";

  return (
    <div>
      <div style={headerStyle}>
        <div>
          <strong>Selected node analysis</strong>
          <p style={mutedParagraphStyle}>
            {selectedPoint.nodeName || "Unknown node"} /{" "}
            {selectedPoint.nodeId || "Unknown ID"}
          </p>
          <p style={mutedParagraphStyle}>Source: {sourceLabel}</p>
        </div>
        <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
          {errorMessage && (
            <button type="button" onClick={onRetry}>
              Retry
            </button>
          )}
          <button type="button" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
      {loading && (
        <p style={mutedParagraphStyle}>Loading node-based demand...</p>
      )}
      {errorMessage && (
        <p
          style={{
            ...mutedParagraphStyle,
            color: "#b00020",
            whiteSpace: "pre-line"
          }}
        >
          {errorMessage}
        </p>
      )}
    </div>
  );
}

/**
 * Renders the selected point details.
 */
function SelectedPointSection({ selectedPoint }) {
  const fromSearch = selectedPoint.source === "node-search";
  const boarding = Number(selectedPoint.boarding || 0);
  const alighting = Number(selectedPoint.alighting || 0);
  const total = boarding + alighting;

  return (
    <section style={subSectionStyle}>
      <strong>Selected point</strong>
      <p style={mutedParagraphStyle}>
        {fromSearch
          ? "This node was opened from search. Route-specific marker demand is not attached to the search result."
          : "This is the clicked marker value from the currently loaded route layer."}
      </p>
      <dl style={definitionGridStyle}>
        <dt>Mode</dt>
        <dd style={definitionValueStyle}>{selectedPoint.mode || "Unknown"}</dd>
        <dt>Route</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? "Node search" : selectedPoint.serviceId || "Unknown"}
        </dd>
        <dt>Node ID</dt>
        <dd style={definitionValueStyle}>
          {selectedPoint.nodeId || "Unknown"}
        </dd>
        <dt>Boarding</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? "N/A" : boarding.toLocaleString()}
        </dd>
        <dt>Alighting</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? "N/A" : alighting.toLocaleString()}
        </dd>
        <dt>Total</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? "N/A" : total.toLocaleString()}
        </dd>
        <dt>Latitude</dt>
        <dd style={definitionValueStyle}>
          {formatCoordinate(selectedPoint.lat)}
        </dd>
        <dt>Longitude</dt>
        <dd style={definitionValueStyle}>
          {formatCoordinate(selectedPoint.lng)}
        </dd>
      </dl>
    </section>
  );
}

/**
 * Renders the all-route demand section for the selected node.
 */
function NodeAllRoutesSection({
  nodeDetail,
  routes,
  selectedSubwayLines,
  selectedBusLines,
  onAddRouteFromNode
}) {
  return (
    <section style={subSectionStyle}>
      <strong>
        This node, all routes ({routes.length} route
        {routes.length === 1 ? "" : "s"})
      </strong>
      <dl style={definitionGridStyle}>
        <dt>Boarding</dt>
        <dd style={definitionValueStyle}>
          {Number(nodeDetail.boarding || 0).toLocaleString()}
        </dd>
        <dt>Alighting</dt>
        <dd style={definitionValueStyle}>
          {Number(nodeDetail.alighting || 0).toLocaleString()}
        </dd>
        <dt>Total</dt>
        <dd style={definitionValueStyle}>
          {Number(nodeDetail.total || 0).toLocaleString()}
        </dd>
      </dl>
      <RouteList
        routes={routes}
        selectedSubwayLines={selectedSubwayLines}
        selectedBusLines={selectedBusLines}
        onAddRouteFromNode={onAddRouteFromNode}
      />
    </section>
  );
}

/**
 * Renders an empty-state catchment section that still allows radius switching.
 */
function CatchmentEmptySection({ radiusMeters, onRadiusMetersChange }) {
  return (
    <section style={subSectionStyle}>
      <RadiusSelector
        radiusMeters={radiusMeters}
        onRadiusMetersChange={onRadiusMetersChange}
      />
      <p style={mutedParagraphStyle}>
        No catchment demand is available for the current filters.
      </p>
    </section>
  );
}

/**
 * Renders radius-based catchment demand around the selected node.
 */
function CatchmentSection({
  catchment,
  nodes,
  routes,
  radiusMeters,
  onRadiusMetersChange,
  catchmentLoading,
  showCatchmentSummary,
  showNearbyNodes,
  showRoutes,
  selectedSubwayLines,
  selectedBusLines,
  onAddRouteFromNode
}) {
  return (
    <section style={subSectionStyle}>
      <RadiusSelector
        radiusMeters={radiusMeters}
        onRadiusMetersChange={onRadiusMetersChange}
        catchmentLoading={catchmentLoading}
      />
      <p style={mutedParagraphStyle}>
        Catchment demand is radius-based and is not clipped by selected
        administrative districts.
      </p>
      {showCatchmentSummary && (
        <dl style={definitionGridStyle}>
          <dt>Nodes</dt>
          <dd style={definitionValueStyle}>
            {(catchment.nodes || []).length.toLocaleString()}
          </dd>
          <dt>Routes</dt>
          <dd style={definitionValueStyle}>
            {(catchment.routes || []).length.toLocaleString()}
          </dd>
          <dt>Boarding</dt>
          <dd style={definitionValueStyle}>
            {Number(catchment.boarding || 0).toLocaleString()}
          </dd>
          <dt>Alighting</dt>
          <dd style={definitionValueStyle}>
            {Number(catchment.alighting || 0).toLocaleString()}
          </dd>
          <dt>Total</dt>
          <dd style={definitionValueStyle}>
            {Number(catchment.total || 0).toLocaleString()}
          </dd>
        </dl>
      )}
      {showNearbyNodes && <NodeList nodes={nodes} />}
      {showRoutes && (
        <RouteList
          routes={routes}
          title="Routes in radius"
          selectedSubwayLines={selectedSubwayLines}
          selectedBusLines={selectedBusLines}
          onAddRouteFromNode={onAddRouteFromNode}
        />
      )}
    </section>
  );
}

/**
 * Renders the radius radio control.
 */
function RadiusSelector({ radiusMeters, onRadiusMetersChange, catchmentLoading }) {
  return (
    <div
      style={{
        display: "flex",
        gap: "12px",
        alignItems: "center",
        flexWrap: "wrap"
      }}
    >
      <strong>Catchment radius</strong>
      <fieldset
        style={{
          display: "flex",
          gap: "8px",
          alignItems: "center",
          border: 0,
          padding: 0,
          margin: 0
        }}
      >
        <legend style={visuallyHiddenStyle}>Catchment radius</legend>
        {[400, 800, 1000].map((radius) => (
          <label
            key={radius}
            style={{ display: "flex", gap: "4px", alignItems: "center" }}
          >
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
  );
}

/**
 * Renders the nearby nodes list with distance and total.
 */
function NodeList({ nodes }) {
  if (nodes.length === 0) {
    return (
      <p style={mutedParagraphStyle}>
        No nearby nodes found for the selected radius.
      </p>
    );
  }
  return (
    <div style={{ marginTop: "10px" }}>
      <strong>Nearby nodes</strong>
      <ol style={scrollListStyle}>
        {nodes.slice(0, 60).map((node) => (
          <li key={`${node.mode}-${node.nodeId}-${node.distanceMeters}`}>
            [{node.mode}] {node.nodeName} —{" "}
            {Math.round(Number(node.distanceMeters || 0)).toLocaleString()}m —{" "}
            {Number(node.total || 0).toLocaleString()}
          </li>
        ))}
      </ol>
    </div>
  );
}

/**
 * Renders a route list with an "add to selected routes" button per row.
 */
function RouteList({
  routes,
  title = "Route breakdown",
  selectedSubwayLines = [],
  selectedBusLines = [],
  onAddRouteFromNode
}) {
  if (routes.length === 0) {
    return (
      <p style={mutedParagraphStyle}>
        No route demand is available for this selection.
      </p>
    );
  }
  return (
    <div style={{ marginTop: "10px" }}>
      <strong>{title}</strong>
      <ol style={scrollListStyle}>
        {routes.slice(0, 60).map((route) => {
          const isSelected = isRouteSelected(
            route,
            selectedSubwayLines,
            selectedBusLines
          );
          return (
            <li key={`${route.mode}-${route.serviceId}`}>
              [{route.mode}] {route.serviceId} —{" "}
              {Number(route.total || 0).toLocaleString()} (board{" "}
              {Number(route.boarding || 0).toLocaleString()} / alight{" "}
              {Number(route.alighting || 0).toLocaleString()}){" "}
              <button
                type="button"
                onClick={() => onAddRouteFromNode?.(route)}
                disabled={isSelected}
              >
                {isSelected ? "Already selected" : "Add to selected routes"}
              </button>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/**
 * Returns true when the given route is already in the parent selection.
 */
function isRouteSelected(route, selectedSubwayLines, selectedBusLines) {
  if (route.mode === "subway") {
    return selectedSubwayLines.includes(route.serviceId);
  }
  if (route.mode === "bus") {
    return selectedBusLines.includes(route.serviceId);
  }
  return false;
}

function formatCoordinate(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric.toFixed(6) : "Unknown";
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

const mutedParagraphStyle = { margin: "6px 0 0", color: "#555555" };

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
