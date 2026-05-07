/**
 * Supported day type options.
 */
const DAY_TYPE_OPTIONS = [
  { value: "mon", label: "Monday" },
  { value: "tue", label: "Tuesday" },
  { value: "wed", label: "Wednesday" },
  { value: "thu", label: "Thursday" },
  { value: "fri", label: "Friday" },
  { value: "sat", label: "Saturday" },
  { value: "sun_holiday", label: "Sunday / Holiday" }
];

/**
 * Supported metric options.
 */
const METRIC_OPTIONS = [
  { value: "total", label: "Total" },
  { value: "boarding", label: "Boarding" },
  { value: "alighting", label: "Alighting" }
];

/**
 * Control panel for demand exploration.
 *
 * This component does not fetch demand data by itself.
 * It only lets the user select filters and explicitly request loading.
 */
export default function DemandControlPanel({
  mode,
  setMode,
  dayType,
  setDayType,
  hour,
  setHour,
  line,
  setLine,
  metric,
  setMetric,
  lines = [],
  lineLoading,
  lineError,
  onLoadDemand,
  hasAppliedFilters
}) {
  /**
   * Changes transport mode.
   * The parent component resets the line list through mode-dependent loading.
   */
  const handleModeChange = (event) => {
    setMode(event.target.value);
  };

  return (
    <section className="control-panel">
      <div>
        <h1>Seoul Transit Demand Map</h1>
        <p>
          Select a mode, line, day type, hour, and metric, then load the demand layer.
        </p>
      </div>

      <div className="control-grid">
        <label>
          Mode
          <select
            value={mode}
            onChange={handleModeChange}
          >
            <option value="subway">Subway</option>
            <option value="bus">Bus</option>
          </select>
        </label>

        <label>
          Line
          <select
            value={line}
            onChange={(event) => setLine(event.target.value)}
            disabled={lineLoading}
          >
            <option value="">
              {lineLoading ? "Loading lines..." : "Select a line"}
            </option>
            {lines.map((lineName) => (
              <option
                key={lineName}
                value={lineName}
              >
                {lineName}
              </option>
            ))}
          </select>
        </label>

        <label>
          Day Type
          <select
            value={dayType}
            onChange={(event) => setDayType(event.target.value)}
          >
            {DAY_TYPE_OPTIONS.map((option) => (
              <option
                key={option.value}
                value={option.value}
              >
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label>
          Hour: {String(hour).padStart(2, "0")}:00
          <input
            type="range"
            min="0"
            max="23"
            value={hour}
            onChange={(event) => setHour(Number(event.target.value))}
          />
        </label>

        <label>
          Metric
          <select
            value={metric}
            onChange={(event) => setMetric(event.target.value)}
          >
            {METRIC_OPTIONS.map((option) => (
              <option
                key={option.value}
                value={option.value}
              >
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <button
          type="button"
          onClick={onLoadDemand}
          disabled={!line || lineLoading}
        >
          Load demand
        </button>
      </div>

      <div className="status-row">
        <span>
          {lineError
            ? lineError
            : hasAppliedFilters
              ? "Demand layer loaded. Change filters and click Load again."
              : "No demand layer loaded yet."}
        </span>
      </div>
    </section>
  );
}
