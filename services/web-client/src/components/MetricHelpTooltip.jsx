/**
 * Small inline explanation block for demand metrics.
 *
 * A text card is used instead of a hover-only tooltip so the explanation
 * remains usable on touch devices.
 */
export default function MetricHelpTooltip({ label, description }) {
  return (
    <div className="metric-help">
      <strong>{label}</strong>
      <p className="note">{description}</p>
    </div>
  );
}
