/**
 * Describes current and planned analytical methodology.
 *
 * The current map focuses on demand visualization. OD flow and congestion
 * metrics are documented as planned analytical layers.
 */
export default function MethodologyPanel() {
  return (
    <section
      className="methodology-panel"
      style={{
        padding: "10px 12px",
        border: "1px solid #eeeeee",
        borderRadius: "8px",
        backgroundColor: "#fcfcfc"
      }}
    >
      <h3 style={{ margin: "0 0 6px" }}>Methodology notes</h3>

      <ul style={{ margin: 0, paddingLeft: "20px" }}>
        <li>
          Current demand values are aggregated from the selected mode, route,
          day type, and hour range.
        </li>
        <li>
          Administrative-district filtering is calculated spatially by checking
          whether visible points fall inside selected district polygons.
        </li>
        <li>
          Future OD flow layers should aggregate origin and destination records
          into selected administrative areas or drawn map regions.
        </li>
        <li>
          Future congestion metrics should combine demand, service frequency,
          and estimated capacity. Current demand markers should not be read as
          direct crowding indicators.
        </li>
      </ul>
    </section>
  );
}
