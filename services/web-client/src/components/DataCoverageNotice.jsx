import { useState } from "react";
import MetricHelpTooltip from "./MetricHelpTooltip.jsx";
import MethodologyPanel from "./MethodologyPanel.jsx";

/**
 * Explains data coverage, known limitations, and interpretation rules.
 *
 * This component is intentionally text-first because the service is an
 * analytical dashboard rather than a decorative map.
 */
export default function DataCoverageNotice() {
  const [expanded, setExpanded] = useState(false);

  return (
    <section
      className="data-coverage-notice"
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
          alignItems: "center",
          justifyContent: "space-between",
          flexWrap: "wrap"
        }}
      >
        <div>
          <strong>Data coverage and interpretation notes</strong>
          <p
            style={{
              margin: "4px 0 0",
              color: "#555555"
            }}
          >
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
        <div
          style={{
            marginTop: "12px",
            display: "grid",
            gap: "12px"
          }}
        >
          <section>
            <h3 style={{ margin: "0 0 6px" }}>What this map shows</h3>
            <p style={{ margin: 0 }}>
              The map displays aggregated boarding, alighting, and total demand
              for selected subway routes, selected bus routes, day type selection,
              day aggregation mode, and hour range.
            </p>
          </section>

          <section>
            <h3 style={{ margin: "0 0 6px" }}>Metric guide</h3>
            <div
              style={{
                display: "flex",
                gap: "8px",
                flexWrap: "wrap"
              }}
            >
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
            <h3 style={{ margin: "0 0 6px" }}>Coverage</h3>
            <ul style={{ margin: 0, paddingLeft: "20px" }}>
              <li>
                Subway and bus routes can be displayed together for exploratory
                multi-modal comparison.
              </li>
              <li>
                Clicking a stop or station opens a detail panel using the
                currently loaded map data.
              </li>
              <li>
                Subway points are displayed when station coordinates can be
                matched to demand records.
              </li>
              <li>
                Bus points use Seoul bus stop coordinates plus curated
                metropolitan / outer-area stop mapping.
              </li>
              <li>
                Only stops and stations with available coordinates are rendered
                on the map.
              </li>
            </ul>
          </section>

          <section>
            <h3 style={{ margin: "0 0 6px" }}>Known limitations</h3>
            <ul style={{ margin: 0, paddingLeft: "20px" }}>
              <li>
                Some virtual, unknown, discontinued, or non-physical stop codes
                may be excluded or represented approximately.
              </li>
              <li>
                Some coordinates may still require later data-quality review,
                especially where historical route data and current stop
                references differ.
              </li>
              <li>
                When multiple day types are selected, demand can be displayed
                either as the sum of selected days or as the average per
                selected day.
              </li>
              <li>
                The node detail panel shows values for the currently loaded
                filter result, not a full historical profile of the stop or
                station.
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
