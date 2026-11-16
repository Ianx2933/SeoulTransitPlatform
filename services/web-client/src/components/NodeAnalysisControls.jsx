import { useI18n } from "../i18n/I18nProvider.jsx";

/** Controls visible analysis sections and list sorting. */
export default function NodeAnalysisControls({
  sections,
  onToggleSection,
  nodeSortKey,
  onNodeSortKeyChange,
  routeSortKey,
  onRouteSortKeyChange
}) {
  const { tx } = useI18n();
  return (
    <section
      style={{
        marginTop: "14px",
        paddingTop: "14px",
        borderTop: "1px solid var(--rule)"
      }}
    >
      <strong>{tx("Analysis target controls")}</strong>
      <p style={mutedParagraphStyle}>{tx("Choose which parts of the node analysis to display. Catchment summary, nearby nodes, and routes in radius can be toggled independently.")}</p>

      <div
        style={{
          display: "flex",
          gap: "10px",
          flexWrap: "wrap",
          marginTop: "8px"
        }}
      >
        <SectionToggle
          label={tx("Selected point")}
          checked={sections.showSelectedPoint}
          onChange={() => onToggleSection("showSelectedPoint")}
        />
        <SectionToggle
          label={tx("This node, all routes")}
          checked={sections.showNodeDetail}
          onChange={() => onToggleSection("showNodeDetail")}
        />
        <SectionToggle
          label={tx("Catchment summary")}
          checked={sections.showCatchment}
          onChange={() => onToggleSection("showCatchment")}
        />
        <SectionToggle
          label={tx("Nearby nodes")}
          checked={sections.showNearbyNodes}
          onChange={() => onToggleSection("showNearbyNodes")}
        />
        <SectionToggle
          label={tx("Routes in radius")}
          checked={sections.showRoutes}
          onChange={() => onToggleSection("showRoutes")}
        />
      </div>

      <div
        style={{
          display: "flex",
          gap: "12px",
          flexWrap: "wrap",
          marginTop: "10px"
        }}
      >
        <label>{tx("Sort nearby nodes")}<select
            value={nodeSortKey}
            onChange={(event) => onNodeSortKeyChange(event.target.value)}
            style={{ marginLeft: "8px" }}
          >
            <option value="distanceAsc">{tx("Distance ↑")}</option>
            <option value="distanceDesc">{tx("Distance ↓")}</option>
            <option value="totalDesc">{tx("Demand ↓")}</option>
            <option value="totalAsc">{tx("Demand ↑")}</option>
            <option value="nameAsc">{tx("Name A-Z")}</option>
            <option value="nameDesc">{tx("Name Z-A")}</option>
          </select>
        </label>

        <label>{tx("Sort routes")}<select
            value={routeSortKey}
            onChange={(event) => onRouteSortKeyChange(event.target.value)}
            style={{ marginLeft: "8px" }}
          >
            <option value="totalDesc">{tx("Demand ↓")}</option>
            <option value="totalAsc">{tx("Demand ↑")}</option>
            <option value="routeAsc">{tx("Route A-Z")}</option>
            <option value="routeDesc">{tx("Route Z-A")}</option>
            <option value="boardingDesc">{tx("Boarding ↓")}</option>
            <option value="alightingDesc">{tx("Alighting ↓")}</option>
          </select>
        </label>
      </div>
    </section>
  );
}

/** Renders one visibility toggle. */
function SectionToggle({ label, checked, onChange }) {
  const { tx } = useI18n();
  return (
    <label style={{ display: "flex", gap: "4px", alignItems: "center" }}>
      <input type="checkbox" checked={checked} onChange={onChange} />
      {label}
    </label>
  );
}

const mutedParagraphStyle = { margin: "6px 0 0", color: "var(--muted)", fontSize: "13px" };
