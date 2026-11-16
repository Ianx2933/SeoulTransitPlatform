import { useI18n } from "../i18n/I18nProvider.jsx";
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
  const { tx } = useI18n();
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
            <strong>{tx("This node, all routes")}</strong>
            <p style={mutedParagraphStyle}>{tx("No node-level demand is available for the current filters.")}</p>
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
  const { tx } = useI18n();
  const sourceLabel =
    selectedPoint.source === "node-search" ? tx("Stop search") : tx("Map marker");

  return (
    <div>
      <div style={headerStyle}>
        <div>
          <strong>{tx("Selected node analysis")}</strong>
          <p style={mutedParagraphStyle}>
            {selectedPoint.nodeName || tx("Unknown stop")} /{" "}
            {selectedPoint.nodeId || tx("Unknown ID")}
          </p>
          <p style={mutedParagraphStyle}>{tx("Opened from: {source}", { source: sourceLabel })}</p>
        </div>
        <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
          {errorMessage && (
            <button type="button" onClick={onRetry}>{tx("Retry")}</button>
          )}
          <button type="button" onClick={onClose}>{tx("Close")}</button>
        </div>
      </div>
      {loading && (
        <p style={mutedParagraphStyle}>{tx("Loading node-based demand...")}</p>
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
  const { tx } = useI18n();
  const fromSearch = selectedPoint.source === "node-search";
  const boarding = Number(selectedPoint.boarding || 0);
  const alighting = Number(selectedPoint.alighting || 0);
  const total = boarding + alighting;

  return (
    <section style={subSectionStyle}>
      <strong>{tx("Selected point")}</strong>
      <p style={mutedParagraphStyle}>
        {fromSearch
          ? tx("This node was opened from search. Route-specific marker demand is not attached to the search result.")
          : tx("This is the clicked marker value from the currently loaded route layer.")}
      </p>
      <dl style={definitionGridStyle}>
        <dt>{tx("Mode")}</dt>
        <dd style={definitionValueStyle}>{modeName(tx, selectedPoint.mode)}</dd>
        <dt>{tx("Route")}</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? tx("Stop search") : selectedPoint.serviceId || tx("Unknown")}
        </dd>
        <dt>{tx("Node ID")}</dt>
        <dd style={definitionValueStyle}>
          {selectedPoint.nodeId || tx("Unknown")}
        </dd>
        <dt>{tx("Boarding")}</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? tx("N/A") : boarding.toLocaleString()}
        </dd>
        <dt>{tx("Alighting")}</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? tx("N/A") : alighting.toLocaleString()}
        </dd>
        <dt>{tx("Total")}</dt>
        <dd style={definitionValueStyle}>
          {fromSearch ? tx("N/A") : total.toLocaleString()}
        </dd>
        <dt>{tx("Latitude")}</dt>
        <dd style={definitionValueStyle}>
          {formatCoordinate(selectedPoint.lat)}
        </dd>
        <dt>{tx("Longitude")}</dt>
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
  const { tx } = useI18n();
  return (
    <section style={subSectionStyle}>
      <strong>
        {tx("This node, all routes ({count})", { count: routes.length })}
      </strong>
      <dl style={definitionGridStyle}>
        <dt>{tx("Boarding")}</dt>
        <dd style={definitionValueStyle}>
          {Number(nodeDetail.boarding || 0).toLocaleString()}
        </dd>
        <dt>{tx("Alighting")}</dt>
        <dd style={definitionValueStyle}>
          {Number(nodeDetail.alighting || 0).toLocaleString()}
        </dd>
        <dt>{tx("Total")}</dt>
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
  const { tx } = useI18n();
  return (
    <section style={subSectionStyle}>
      <RadiusSelector
        radiusMeters={radiusMeters}
        onRadiusMetersChange={onRadiusMetersChange}
      />
      <p style={mutedParagraphStyle}>{tx("No catchment demand is available for the current filters.")}</p>
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
  const { tx } = useI18n();
  return (
    <section style={subSectionStyle}>
      <RadiusSelector
        radiusMeters={radiusMeters}
        onRadiusMetersChange={onRadiusMetersChange}
        catchmentLoading={catchmentLoading}
      />
      <p style={mutedParagraphStyle}>{tx("Catchment demand is radius-based and is not clipped by selected administrative districts.")}</p>
      {showCatchmentSummary && (
        <dl style={definitionGridStyle}>
          <dt>{tx("Nodes")}</dt>
          <dd style={definitionValueStyle}>
            {(catchment.nodes || []).length.toLocaleString()}
          </dd>
          <dt>{tx("Routes")}</dt>
          <dd style={definitionValueStyle}>
            {(catchment.routes || []).length.toLocaleString()}
          </dd>
          <dt>{tx("Boarding")}</dt>
          <dd style={definitionValueStyle}>
            {Number(catchment.boarding || 0).toLocaleString()}
          </dd>
          <dt>{tx("Alighting")}</dt>
          <dd style={definitionValueStyle}>
            {Number(catchment.alighting || 0).toLocaleString()}
          </dd>
          <dt>{tx("Total")}</dt>
          <dd style={definitionValueStyle}>
            {Number(catchment.total || 0).toLocaleString()}
          </dd>
        </dl>
      )}
      {showNearbyNodes && <NodeList nodes={nodes} />}
      {showRoutes && (
        <RouteList
          routes={routes}
          title={tx("Routes in radius")}
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
  const { tx } = useI18n();
  return (
    <div
      style={{
        display: "flex",
        gap: "12px",
        alignItems: "center",
        flexWrap: "wrap"
      }}
    >
      <strong>{tx("Catchment radius")}</strong>
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
        <legend style={visuallyHiddenStyle}>{tx("Catchment radius")}</legend>
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
      {catchmentLoading && <span>{tx("Updating catchment...")}</span>}
    </div>
  );
}

/**
 * Renders the nearby nodes list with distance and total.
 */
function NodeList({ nodes }) {
  const { tx } = useI18n();
  if (nodes.length === 0) {
    return (
      <p style={mutedParagraphStyle}>{tx("No nearby nodes found for the selected radius.")}</p>
    );
  }
  return (
    <div style={{ marginTop: "10px" }}>
      <strong>{tx("Nearby nodes")}</strong>
      <ol style={scrollListStyle}>
        {nodes.slice(0, 60).map((node) => (
          <li key={`${node.mode}-${node.nodeId}-${node.distanceMeters}`}>
            <span className="tag">{modeName(tx, node.mode)}</span> {node.nodeName} —{" "}
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
  title,
  selectedSubwayLines = [],
  selectedBusLines = [],
  onAddRouteFromNode
}) {
  const { tx } = useI18n();
  if (routes.length === 0) {
    return (
      <p style={mutedParagraphStyle}>{tx("No route demand is available for this selection.")}</p>
    );
  }
  return (
    <div style={{ marginTop: "10px" }}>
      <strong>{title || tx("Route breakdown")}</strong>
      <ol style={scrollListStyle}>
        {routes.slice(0, 60).map((route) => {
          const isSelected = isRouteSelected(
            route,
            selectedSubwayLines,
            selectedBusLines
          );
          return (
            <li key={`${route.mode}-${route.serviceId}`}>
              <span className="tag">{modeName(tx, route.mode)}</span> {route.serviceId} —{" "}
              {tx("{total} (on {on} / off {off})", {
                total: Number(route.total || 0).toLocaleString(),
                on: Number(route.boarding || 0).toLocaleString(),
                off: Number(route.alighting || 0).toLocaleString()
              })}{" "}
              <button
                type="button"
                onClick={() => onAddRouteFromNode?.(route)}
                disabled={isSelected}
              >
                {isSelected ? tx("Already selected") : tx("Add to selected routes")}
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

/** Mode name in the interface language, or the raw mode. */
function modeName(tx, mode) {
  const names = { subway: "Subway", bus: "Bus", ferry: "Han River bus" };
  return names[mode] ? tx(names[mode]) : mode || tx("Unknown");
}

function formatCoordinate(value) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric.toFixed(6) : "-";
}

const panelStyle = {
  display: "grid",
  gap: "4px",
  padding: "18px 20px",
  border: "1px solid var(--rule)",
  borderRadius: "var(--radius-panel)",
  backgroundColor: "var(--surface)",
  fontSize: "14px",
  lineHeight: 1.6
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
  paddingTop: "14px",
  borderTop: "1px solid var(--rule)"
};

const definitionGridStyle = {
  display: "grid",
  gridTemplateColumns: "max-content 1fr",
  gap: "6px 16px",
  margin: "10px 0 0",
  fontVariantNumeric: "tabular-nums"
};

const definitionValueStyle = { margin: 0 };

const mutedParagraphStyle = { margin: "6px 0 0", color: "var(--muted)", fontSize: "13px" };

const scrollListStyle = {
  maxHeight: "280px",
  overflowY: "auto",
  margin: "8px 0 0",
  paddingLeft: "20px",
  paddingRight: "8px",
  display: "grid",
  gap: "6px"
};

const visuallyHiddenStyle = {
  position: "absolute",
  width: "1px",
  height: "1px",
  overflow: "hidden"
};
