import { useI18n } from "../i18n/I18nProvider.jsx";
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
  const { tx } = useI18n();
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
          <strong>{tx("District-centered demand")}</strong>
          <p style={mutedParagraphStyle}>
            {tx("{names}: boarding and alighting at stops inside the chosen districts, not district-to-district OD.", { names: districtNames })}
          </p>
        </div>
        <div style={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
          {errorMessage && (
            <button type="button" onClick={onRetry}>{tx("Retry district demand")}</button>
          )}
          <button type="button" onClick={onClear}>{tx("Clear districts")}</button>
        </div>
      </div>

      {!filtersApplied && (
        <p style={mutedParagraphStyle}>{tx("Select day/hour filters and click Load demand first. District demand does not require selected routes, but it uses the applied analysis filters.")}</p>
      )}

      {filtersApplied && loading && <p style={mutedParagraphStyle}>{tx("Loading district demand...")}</p>}

      {filtersApplied && errorMessage && (
        <p style={errorTextStyle}>{tx("District demand error: {message}", { message: errorMessage })}</p>
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
  const { tx } = useI18n();
  if (unmatchedDistrictCodes.length === 0) return null;

  return (
    <p style={warningTextStyle}>
      {tx("Warning: {count} chosen district codes matched no boundary and are left out of the totals: {codes}. The map layer and the database may use different code systems.", {
        count: unmatchedDistrictCodes.length,
        codes: unmatchedDistrictCodes.join(", ")
      })}
    </p>
  );
}

/** Renders aggregate district totals. */
function DistrictSummary({ districtDemand }) {
  const { tx } = useI18n();
  return (
    <section style={subSectionStyle}>
      <strong>{tx("All routes inside selected district(s)")}</strong>
      <div style={{ marginTop: "6px" }}>
        {tx("Boarding {on} | Alighting {off} | Total {total}", {
          on: Number(districtDemand.boarding || 0).toLocaleString(),
          off: Number(districtDemand.alighting || 0).toLocaleString(),
          total: Number(districtDemand.total || 0).toLocaleString()
        })}
      </div>
    </section>
  );
}

/** Renders bus/subway totals.*/
function ModeBreakdown({ modes }) {
  const { tx } = useI18n();
  if (modes.length === 0) return null;

  return (
    <section style={subSectionStyle}>
      <strong>{tx("Mode breakdown")}</strong>
      <ul style={compactListStyle}>
        {modes.map((mode) => (
          <li key={mode.mode}>
            <span className="tag">{modeName(tx, mode.mode)}</span>{" "}
            {tx("{total} (on {on} / off {off})", {
              total: Number(mode.total || 0).toLocaleString(),
              on: Number(mode.boarding || 0).toLocaleString(),
              off: Number(mode.alighting || 0).toLocaleString()
            })}
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
  const { tx } = useI18n();
  return (
    <section style={subSectionStyle}>
      <div style={toolbarStyle}>
        <strong>{tx("Routes inside district")}</strong>
        <SortSelect value={sortKey} onChange={onSortKeyChange} type="route" />
      </div>
      {routes.length === 0 ? (
        <p style={mutedParagraphStyle}>{tx("No route demand is available for this district.")}</p>
      ) : (
        <>
          {routes.length > ROUTE_DISPLAY_LIMIT && (
            <p style={mutedParagraphStyle}>
              {tx("Showing the first {shown} of {all} routes in the current order.", {
                shown: ROUTE_DISPLAY_LIMIT.toLocaleString(),
                all: routes.length.toLocaleString()
              })}
            </p>
          )}
        <ol style={scrollListStyle}>
          {routes.slice(0, ROUTE_DISPLAY_LIMIT).map((route) => {
            const isSelected = isRouteSelected(route, selectedSubwayLines, selectedBusLines);
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
  const { tx } = useI18n();
  const shownCount = Math.min(nodes.length, NODE_DISPLAY_LIMIT);
  const fullCount = Math.max(Number(totalNodeCount || 0), nodes.length);
  const isTruncated = shownCount < fullCount;

  return (
    <section style={subSectionStyle}>
      <div style={toolbarStyle}>
        <strong>{tx("Nodes inside district")}</strong>
        <SortSelect value={sortKey} onChange={onSortKeyChange} type="node" />
      </div>
      {nodes.length === 0 ? (
        <p style={mutedParagraphStyle}>{tx("No stop or station demand is available for this district.")}</p>
      ) : (
        <>
          {isTruncated && (
            <p style={mutedParagraphStyle}>
              {tx("Showing the first {shown} of {all} route-stop rows. The totals above use every row.", {
                shown: shownCount.toLocaleString(),
                all: fullCount.toLocaleString()
              })}
            </p>
          )}
        <ol style={scrollListStyle}>
          {nodes.slice(0, NODE_DISPLAY_LIMIT).map((node) => (
            <li key={`${node.mode}-${node.serviceId}-${node.nodeId}`}>
              <span className="tag">{modeName(tx, node.mode)}</span> {node.serviceId} / {node.nodeName} ({node.nodeId}) —{" "}
              {tx("{total} (on {on} / off {off})", {
                total: Number(node.total || 0).toLocaleString(),
                on: Number(node.boarding || 0).toLocaleString(),
                off: Number(node.alighting || 0).toLocaleString()
              })}
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
  const { tx } = useI18n();
  return (
    <label style={{ display: "flex", gap: "6px", alignItems: "center" }}>
      {tx("Sort")}
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="totalDesc">{tx("Total ↓")}</option>
        <option value="totalAsc">{tx("Total ↑")}</option>
        <option value="boardingDesc">{tx("Boarding ↓")}</option>
        <option value="alightingDesc">{tx("Alighting ↓")}</option>
        {type === "route" ? (
          <>
            <option value="routeAsc">{tx("Route ↑")}</option>
            <option value="routeDesc">{tx("Route ↓")}</option>
          </>
        ) : (
          <>
            <option value="nameAsc">{tx("Name ↑")}</option>
            <option value="nameDesc">{tx("Name ↓")}</option>
            <option value="routeAsc">{tx("Route ↑")}</option>
            <option value="routeDesc">{tx("Route ↓")}</option>
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
/** Mode name in the interface language, or the raw mode. */
function modeName(tx, mode) {
  const names = { subway: "Subway", bus: "Bus", ferry: "Han River bus" };
  return names[mode] ? tx(names[mode]) : mode || tx("Unknown");
}

function isRouteSelected(route, selectedSubwayLines, selectedBusLines) {
  if (route.mode === "subway") return selectedSubwayLines.includes(route.serviceId);
  if (route.mode === "bus") return selectedBusLines.includes(route.serviceId);
  return false;
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

const toolbarStyle = {
  display: "flex",
  gap: "10px",
  justifyContent: "space-between",
  alignItems: "center",
  flexWrap: "wrap"
};

const compactListStyle = {
  margin: "8px 0 0",
  paddingLeft: "20px",
  display: "grid",
  gap: "4px"
};

const scrollListStyle = {
  maxHeight: "280px",
  overflowY: "auto",
  margin: "8px 0 0",
  paddingLeft: "20px",
  paddingRight: "8px",
  display: "grid",
  gap: "6px"
};

const mutedParagraphStyle = { margin: "6px 0 0", color: "var(--muted)", fontSize: "13px" };

const errorTextStyle = {
  margin: "8px 0 0",
  color: "var(--danger)"
};

const warningTextStyle = {
  margin: "10px 0 0",
  padding: "8px 12px",
  color: "#7a4a00",
  backgroundColor: "#fff4e0",
  borderLeft: "3px solid var(--selected)",
  borderRadius: "4px"
};
