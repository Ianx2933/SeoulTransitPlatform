import { useMemo, useState } from "react";

import {
  sortDistrictNodes,
  sortDistrictRoutes
} from "../utils/districtDemandSort.js";

/** Maximum route rows rendered in the panel. */
const ROUTE_DISPLAY_LIMIT = 80;

/** Maximum route-node rows rendered in the panel. */
const NODE_DISPLAY_LIMIT = 120;

/** Displays district-centered all-route demand. */
export default function DistrictDemandPanel({
  selectedDistricts = [],
  districtDemand,
  filtersApplied,
  loading,
  errorMessage,
  onRetry,
  onClear,
  selectedSubwayLines = [],
  selectedBusLines = [],
  onAddRouteFromNode
}) {
  const [routeSortKey, setRouteSortKey] = useState("totalDesc");
  const [nodeSortKey, setNodeSortKey] = useState("totalDesc");

  const sortedRoutes = useMemo(
    () => sortDistrictRoutes(districtDemand?.routes || [], routeSortKey),
    [districtDemand, routeSortKey]
  );

  const sortedNodes = useMemo(
    () => sortDistrictNodes(districtDemand?.nodes || [], nodeSortKey),
    [districtDemand, nodeSortKey]
  );

  if (selectedDistricts.length === 0) return null;

  const districtNames = selectedDistricts
    .map((district) => district.districtName || district.districtCode)
    .join(", ");

  return (
    <section className="district-demand-panel" style={panelStyle}>
      <div style={headerStyle}>
        <div>
          <strong>District-centered demand</strong>
          <p style={mutedParagraphStyle}>
            {districtNames} — node activity inside selected administrative
            polygons, not district-to-district OD flow.
          </p>
        </div>
        <div style={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
          {errorMessage && (
            <button type="button" onClick={onRetry}>
              Retry district demand
            </button>
          )}
          <button type="button" onClick={onClear}>
            Clear districts
          </button>
        </div>
      </div>

      {!filtersApplied && (
        <p style={mutedParagraphStyle}>
          Select day/hour filters and click Load demand first. District demand
          does not require selected routes, but it uses the applied analysis
          filters.
        </p>
      )}

      {filtersApplied && loading && <p style={mutedParagraphStyle}>Loading district demand...</p>}

      {filtersApplied && errorMessage && (
        <p style={errorTextStyle}>District demand error: {errorMessage}</p>
      )}

      {filtersApplied && !loading && !errorMessage && districtDemand && (
        <>
          <UnmatchedDistrictWarning
            unmatchedDistrictCodes={districtDemand.unmatchedDistrictCodes || []}
          />
          <DistrictSummary districtDemand={districtDemand} />
          <ModeBreakdown modes={districtDemand.modes || []} />
          <RouteBreakdown
            routes={sortedRoutes}
            sortKey={routeSortKey}
            onSortKeyChange={setRouteSortKey}
            selectedSubwayLines={selectedSubwayLines}
            selectedBusLines={selectedBusLines}
            onAddRouteFromNode={onAddRouteFromNode}
          />
          <NodeBreakdown
            nodes={sortedNodes}
            totalNodeCount={Number(districtDemand.totalNodeCount || sortedNodes.length)}
            sortKey={nodeSortKey}
            onSortKeyChange={setNodeSortKey}
          />
        </>
      )}
    </section>
  );
}

/**
 * Warns about district codes that matched no boundary row, e.g. a code-system
 * mismatch between the GeoJSON layer and the DB.
 */
function UnmatchedDistrictWarning({ unmatchedDistrictCodes }) {
  if (unmatchedDistrictCodes.length === 0) return null;

  return (
    <p style={warningTextStyle}>
      Warning: {unmatchedDistrictCodes.length} selected district code
      {unmatchedDistrictCodes.length === 1 ? "" : "s"} matched no boundary row
      and {unmatchedDistrictCodes.length === 1 ? "is" : "are"} excluded from
      totals: {unmatchedDistrictCodes.join(", ")}. Check for a code-system
      mismatch between the map layer and the database.
    </p>
  );
}

/** Renders aggregate district totals. (행정동 집계 합계를 렌더링합니다.) */
function DistrictSummary({ districtDemand }) {
  return (
    <section style={subSectionStyle}>
      <strong>All routes inside selected district(s)</strong>
      <div style={{ marginTop: "6px" }}>
        Boarding: {Number(districtDemand.boarding || 0).toLocaleString()} |
        Alighting: {Number(districtDemand.alighting || 0).toLocaleString()} | Total:{" "}
        {Number(districtDemand.total || 0).toLocaleString()}
      </div>
    </section>
  );
}

/** Renders bus/subway totals.*/
function ModeBreakdown({ modes }) {
  if (modes.length === 0) return null;

  return (
    <section style={subSectionStyle}>
      <strong>Mode breakdown</strong>
      <ul style={compactListStyle}>
        {modes.map((mode) => (
          <li key={mode.mode}>
            [{mode.mode}] {Number(mode.total || 0).toLocaleString()} (board{" "}
            {Number(mode.boarding || 0).toLocaleString()} / alight{" "}
            {Number(mode.alighting || 0).toLocaleString()})
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Renders route totals and route-add actions. */
function RouteBreakdown({
  routes,
  sortKey,
  onSortKeyChange,
  selectedSubwayLines,
  selectedBusLines,
  onAddRouteFromNode
}) {
  return (
    <section style={subSectionStyle}>
      <div style={toolbarStyle}>
        <strong>Routes inside district</strong>
        <SortSelect value={sortKey} onChange={onSortKeyChange} type="route" />
      </div>
      {routes.length === 0 ? (
        <p style={mutedParagraphStyle}>No route demand is available for this district.</p>
      ) : (
        <>
          {routes.length > ROUTE_DISPLAY_LIMIT && (
            <p style={mutedParagraphStyle}>
              Showing top {ROUTE_DISPLAY_LIMIT.toLocaleString()} of{" "}
              {routes.length.toLocaleString()} routes by the current sort.
            </p>
          )}
        <ol style={scrollListStyle}>
          {routes.slice(0, ROUTE_DISPLAY_LIMIT).map((route) => {
            const isSelected = isRouteSelected(route, selectedSubwayLines, selectedBusLines);
            return (
              <li key={`${route.mode}-${route.serviceId}`}>
                [{route.mode}] {route.serviceId} — {Number(route.total || 0).toLocaleString()}{" "}
                (board {Number(route.boarding || 0).toLocaleString()} / alight{" "}
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
        </>
      )}
    </section>
  );
}

/**
 * Renders route-node rows inside selected districts.
 *
 * Two truncation layers can apply: the server may cap rows via nodeLimit
 * (nodes.length < totalNodeCount) and this component renders at most
 * NODE_DISPLAY_LIMIT rows. Both are surfaced so users know rows are cut.
 */
function NodeBreakdown({ nodes, totalNodeCount, sortKey, onSortKeyChange }) {
  const shownCount = Math.min(nodes.length, NODE_DISPLAY_LIMIT);
  const fullCount = Math.max(Number(totalNodeCount || 0), nodes.length);
  const isTruncated = shownCount < fullCount;

  return (
    <section style={subSectionStyle}>
      <div style={toolbarStyle}>
        <strong>Nodes inside district</strong>
        <SortSelect value={sortKey} onChange={onSortKeyChange} type="node" />
      </div>
      {nodes.length === 0 ? (
        <p style={mutedParagraphStyle}>No stop or station demand is available for this district.</p>
      ) : (
        <>
          {isTruncated && (
            <p style={mutedParagraphStyle}>
              Showing top {shownCount.toLocaleString()} of{" "}
              {fullCount.toLocaleString()} route-node rows. Totals above are
              computed from all rows.
            </p>
          )}
        <ol style={scrollListStyle}>
          {nodes.slice(0, NODE_DISPLAY_LIMIT).map((node) => (
            <li key={`${node.mode}-${node.serviceId}-${node.nodeId}`}>
              [{node.mode}] {node.serviceId} / {node.nodeName} ({node.nodeId}) —{" "}
              {Number(node.total || 0).toLocaleString()} (board{" "}
              {Number(node.boarding || 0).toLocaleString()} / alight{" "}
              {Number(node.alighting || 0).toLocaleString()})
            </li>
          ))}
        </ol>
        </>
      )}
    </section>
  );
}

/** Renders a small sort selector. */
function SortSelect({ value, onChange, type }) {
  return (
    <label style={{ display: "flex", gap: "6px", alignItems: "center" }}>
      Sort
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="totalDesc">Total ↓</option>
        <option value="totalAsc">Total ↑</option>
        <option value="boardingDesc">Boarding ↓</option>
        <option value="alightingDesc">Alighting ↓</option>
        {type === "route" ? (
          <>
            <option value="routeAsc">Route ↑</option>
            <option value="routeDesc">Route ↓</option>
          </>
        ) : (
          <>
            <option value="nameAsc">Name ↑</option>
            <option value="nameDesc">Name ↓</option>
            <option value="routeAsc">Route ↑</option>
            <option value="routeDesc">Route ↓</option>
          </>
        )}
      </select>
    </label>
  );
}

/**
 * Checks route selection per explicit mode. Unknown modes return false rather
 * than silently falling back to the bus list, so a future third mode cannot
 * be mislabeled as already selected.
 */
function isRouteSelected(route, selectedSubwayLines, selectedBusLines) {
  if (route.mode === "subway") return selectedSubwayLines.includes(route.serviceId);
  if (route.mode === "bus") return selectedBusLines.includes(route.serviceId);
  return false;
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
  marginTop: "12px",
  paddingTop: "10px",
  borderTop: "1px solid #eeeeee"
};

const toolbarStyle = {
  display: "flex",
  gap: "10px",
  justifyContent: "space-between",
  alignItems: "center",
  flexWrap: "wrap"
};

const compactListStyle = {
  margin: "6px 0 0",
  paddingLeft: "20px"
};

const scrollListStyle = {
  maxHeight: "280px",
  overflowY: "auto",
  paddingRight: "12px"
};

const mutedParagraphStyle = {
  margin: "4px 0 0",
  color: "#666666"
};

const errorTextStyle = {
  margin: "8px 0 0",
  color: "#9b1c1c"
};

const warningTextStyle = {
  margin: "10px 0 0",
  padding: "6px 10px",
  color: "#7a4a00",
  backgroundColor: "#fff7e0",
  border: "1px solid #f0c060",
  borderRadius: "6px"
};
