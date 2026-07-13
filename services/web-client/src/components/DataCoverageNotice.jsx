import { useState } from "react";
import MetricHelpTooltip from "./MetricHelpTooltip.jsx";
import MethodologyPanel from "./MethodologyPanel.jsx";

/** Explains data coverage, known limitations, and interpretation rules. */
export default function DataCoverageNotice() {
  const [expanded, setExpanded] = useState(false);

  return (
    <section className="data-coverage-notice" style={noticeStyle}>
      <div style={noticeHeaderStyle}>
        <div>
          <strong>Data coverage and interpretation notes</strong>
          <p style={mutedParagraphStyle}>
            This dashboard shows coordinate-matched transit demand only.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setExpanded((current) => !current)}
          aria-expanded={expanded}
        >
          {expanded ? "Hide details" : "Show details"}
        </button>
      </div>

      {expanded && (
        <div style={detailsContainerStyle}>
          <section>
            <h3 style={sectionHeadingStyle}>What this map shows</h3>
            <p style={{ margin: 0 }}>
              The map displays aggregated boarding, alighting, and total demand
              for selected subway routes, selected bus routes, day type
              selection, day aggregation mode, and hour range.
            </p>
          </section>

          <section>
            <h3 style={sectionHeadingStyle}>Metric guide</h3>
            <div style={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
              <MetricHelpTooltip
                label="Total"
                description="Boarding + alighting demand in the selected time range."
              />
              <MetricHelpTooltip
                label="Boarding"
                description="Passenger boardings at each stop or station."
              />
              <MetricHelpTooltip
                label="Alighting"
                description="Passenger alightings at each stop or station."
              />
            </div>
          </section>

          <section>
            <h3 style={sectionHeadingStyle}>Coverage</h3>
            <ul style={listStyle}>
              <li>
                Subway and bus routes can be displayed together for exploratory
                multi-modal comparison.
              </li>
              <li>
                Node search opens stop/station-centered analysis without
                selecting a route first.
              </li>
              <li>
                Clicking a stop or station opens all-route node detail and
                radius-based catchment analysis.
              </li>
              <li>
                Catchment summary, nearby nodes, and routes in radius are
                separate display controls that use the same radius-based
                catchment API response.
              </li>
              <li>
                Route breakdown rows can be added back into the selected route
                controls for another map load.
              </li>
              <li>
                Administrative boundaries are clickable only when the boundary
                layer is enabled.
              </li>
              <li>
                Clicking an administrative district opens district-centered
                all-route demand when analysis filters are applied.
              </li>
              <li>
                Selected districts persist across Load demand; district-centered
                demand reloads automatically with the newly applied filters.
              </li>
              <li>
                Only stops and stations with available coordinates are rendered
                on the map.
              </li>
            </ul>
          </section>

          <section>
            <h3 style={sectionHeadingStyle}>Known limitations</h3>
            <ul style={listStyle}>
              <li>
                Radius-based catchment is exploratory and is not an official
                transfer area, station catchment, or legal service area.
              </li>
              <li>
                Catchment demand is radius-based and is not clipped by selected
                administrative district polygons.
              </li>
              <li>
                District-centered demand means stop/station activity inside
                selected polygons; it is not district-to-district OD flow.
              </li>
              <li>
                Selected administrative districts still filter the currently
                loaded map points separately from the district-demand API.
              </li>
              <li>
                District demand lists may be truncated for display; totals and
                breakdowns are always computed from all matched rows, and any
                truncation is labeled in the panel.
              </li>
              <li>
                District codes that match no boundary row are reported as a
                warning instead of silently contributing zero demand.
              </li>
              <li>
                Add to selected routes updates route controls; the map refreshes
                after the user clicks Load demand.
              </li>
              <li>
                Demand values do not directly represent onboard crowding,
                vehicle load, or seat availability.
              </li>
            </ul>
          </section>

          <MethodologyPanel />
        </div>
      )}
    </section>
  );
}

const noticeStyle = {
  margin: "12px 16px",
  padding: "12px 14px",
  border: "1px solid #dddddd",
  borderRadius: "10px",
  backgroundColor: "#ffffff",
  fontSize: "14px",
  lineHeight: 1.5
};

const noticeHeaderStyle = {
  display: "flex",
  gap: "12px",
  alignItems: "center",
  justifyContent: "space-between",
  flexWrap: "wrap"
};

const detailsContainerStyle = {
  marginTop: "12px",
  display: "grid",
  gap: "12px"
};

const sectionHeadingStyle = {
  margin: "0 0 6px"
};

const listStyle = {
  margin: 0,
  paddingLeft: "20px"
};

const mutedParagraphStyle = {
  margin: "4px 0 0",
  color: "#555555"
};
