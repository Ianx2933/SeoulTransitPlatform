/** Controls visible analysis sections and list sorting. */
export default function NodeAnalysisControls({
  sections,
  onToggleSection,
  nodeSortKey,
  onNodeSortKeyChange,
  routeSortKey,
  onRouteSortKeyChange
}) {
  return (
    <section
      style={{
        marginTop: "12px",
        paddingTop: "12px",
        borderTop: "1px solid #eeeeee"
      }}
    >
      <strong>Analysis target controls</strong>
      <p style={mutedParagraphStyle}>
        Choose which parts of the node analysis to display. Catchment summary,
        nearby nodes, and routes in radius can be toggled independently.
      </p>

      <div
        style={{
          display: "flex",
          gap: "10px",
          flexWrap: "wrap",
          marginTop: "8px"
        }}
      >
        <SectionToggle
          label="Selected point"
          checked={sections.showSelectedPoint}
          onChange={() => onToggleSection("showSelectedPoint")}
        />
        <SectionToggle
          label="This node, all routes"
          checked={sections.showNodeDetail}
          onChange={() => onToggleSection("showNodeDetail")}
        />
        <SectionToggle
          label="Catchment summary"
          checked={sections.showCatchment}
          onChange={() => onToggleSection("showCatchment")}
        />
        <SectionToggle
          label="Nearby nodes"
          checked={sections.showNearbyNodes}
          onChange={() => onToggleSection("showNearbyNodes")}
        />
        <SectionToggle
          label="Routes in radius"
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
        <label>
          Sort nearby nodes
          <select
            value={nodeSortKey}
            onChange={(event) => onNodeSortKeyChange(event.target.value)}
            style={{ marginLeft: "8px" }}
          >
            <option value="distanceAsc">Distance ↑</option>
            <option value="distanceDesc">Distance ↓</option>
            <option value="totalDesc">Demand ↓</option>
            <option value="totalAsc">Demand ↑</option>
            <option value="nameAsc">Name A-Z</option>
            <option value="nameDesc">Name Z-A</option>
          </select>
        </label>

        <label>
          Sort routes
          <select
            value={routeSortKey}
            onChange={(event) => onRouteSortKeyChange(event.target.value)}
            style={{ marginLeft: "8px" }}
          >
            <option value="totalDesc">Demand ↓</option>
            <option value="totalAsc">Demand ↑</option>
            <option value="routeAsc">Route A-Z</option>
            <option value="routeDesc">Route Z-A</option>
            <option value="boardingDesc">Boarding ↓</option>
            <option value="alightingDesc">Alighting ↓</option>
          </select>
        </label>
      </div>
    </section>
  );
}

/** Renders one visibility toggle. */
function SectionToggle({ label, checked, onChange }) {
  return (
    <label style={{ display: "flex", gap: "4px", alignItems: "center" }}>
      <input type="checkbox" checked={checked} onChange={onChange} />
      {label}
    </label>
  );
}

const mutedParagraphStyle = { margin: "6px 0 0", color: "#555555" };
