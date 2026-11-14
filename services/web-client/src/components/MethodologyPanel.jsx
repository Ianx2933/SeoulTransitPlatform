/**
 * Describes current and planned analytical methodology.
 *
 * The map shows hourly boarding demand; route analysis shows typical-day OD.
 * Congestion against capacity is still a planned layer.
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
          Route analysis uses typical-day OD: for each day type (weekday,
          Saturday, Sunday and public holiday), the mean daily passengers of
          every boarding-to-alighting stop pair over the last twelve months of
          corrected TOPIS route OD, with special days left out. Checked by
          predicting each day from the others, it is within about 6% by stop
          and 3% by route on weekdays; single stop pairs are noisier (about 26%).
        </li>
        <li>
          It has no time of day: on-board figures are daily totals passing each
          stop, not the load of one bus. Future OD flow layers should aggregate
          origin and destination records into administrative areas.
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
