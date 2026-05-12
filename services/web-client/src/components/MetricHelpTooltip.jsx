/**
 * Small inline explanation block for demand metrics.
 *
 * A simple text card is used instead of a hover-only tooltip so the explanation
 * remains usable on touch devices.
 */
export default function MetricHelpTooltip({ label, description }) {
  return (
    <div
      className="metric-help-tooltip"
      style={{
        minWidth: "160px",
        padding: "8px 10px",
        border: "1px solid #e0e0e0",
        borderRadius: "8px",
        backgroundColor: "#fafafa"
      }}
    >
      <strong>{label}</strong>
      <p
        style={{
          margin: "4px 0 0",
          color: "#555555"
        }}
      >
        {description}
      </p>
    </div>
  );
}
