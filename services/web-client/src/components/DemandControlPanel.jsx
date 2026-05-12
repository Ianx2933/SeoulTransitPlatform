import { useMemo, useState } from "react";

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
 * Day type presets for fast multi-day selection.
 */
const DAY_TYPE_PRESETS = [
  { value: "mon", label: "Monday", dayTypes: ["mon"] },
  { value: "tue", label: "Tuesday", dayTypes: ["tue"] },
  { value: "wed", label: "Wednesday", dayTypes: ["wed"] },
  { value: "thu", label: "Thursday", dayTypes: ["thu"] },
  { value: "fri", label: "Friday", dayTypes: ["fri"] },
  {
    value: "weekdays",
    label: "Weekdays",
    dayTypes: ["mon", "tue", "wed", "thu", "fri"]
  },
  {
    value: "weekend",
    label: "Weekend / Holiday",
    dayTypes: ["sat", "sun_holiday"]
  },
  {
    value: "all",
    label: "All days",
    dayTypes: ["mon", "tue", "wed", "thu", "fri", "sat", "sun_holiday"]
  },
  { value: "custom", label: "Custom", dayTypes: [] }
];

/**
 * Supported day aggregation options.
 */
const DAY_AGGREGATION_OPTIONS = [
  {
    value: "average",
    label: "Average per selected day"
  },
  {
    value: "sum",
    label: "Sum of selected days"
  }
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
 * Supported Leaflet tile layer options.
 */
const TILE_LAYER_OPTIONS = [
  { value: "osm", label: "OpenStreetMap" },
  { value: "cartoLight", label: "CartoDB Positron" },
  { value: "cartoDark", label: "CartoDB Dark Matter" }
];

/**
 * Control panel for demand exploration.
 *
 * This component does not fetch demand data by itself.
 *
 * It only lets the user select filters and explicitly request loading.
 */
export default function DemandControlPanel({
  mode,
  setMode,
  dayTypePreset,
  setDayTypePreset,
  selectedDayTypes,
  setSelectedDayTypes,
  dayAggregation,
  setDayAggregation,
  startHour,
  setStartHour,
  endHour,
  setEndHour,
  selectedLines,
  setSelectedLines,
  metric,
  setMetric,
  lines = [],
  lineLoading,
  lineError,
  onLoadDemand,
  hasAppliedFilters,
  showAdminBoundary,
  setShowAdminBoundary,
  selectedTileLayer,
  setSelectedTileLayer
}) {
  /**
   * Local search keyword for the route selector.
   *
   * This keeps route selection usable even when bus routes are numerous.
   */
  const [lineSearch, setLineSearch] = useState("");

  /**
   * Changes transport mode.
   *
   * The parent component resets line state after loading the new mode's line list.
   */
  const handleModeChange = (event) => {
    setMode(event.target.value);
    setLineSearch("");
  };

  /**
   * Applies a predefined day type preset.
   */
  const handleDayTypePresetChange = (event) => {
    const nextPreset = event.target.value;
    const preset = DAY_TYPE_PRESETS.find((item) => item.value === nextPreset);

    setDayTypePreset(nextPreset);

    if (preset && nextPreset !== "custom") {
      setSelectedDayTypes(preset.dayTypes);
    }
  };

  /**
   * Toggles one day type in custom mode.
   */
  const toggleDayType = (dayType) => {
    setDayTypePreset("custom");
    setSelectedDayTypes((currentDayTypes) => {
      if (currentDayTypes.includes(dayType)) {
        return currentDayTypes.filter((value) => value !== dayType);
      }

      return [...currentDayTypes, dayType];
    });
  };

  /**
   * Filters route candidates by the user's keyword.
   *
   * The result is capped to keep the UI compact and to avoid rendering a very
   * long list of bus routes at once.
   */
  const filteredLines = useMemo(() => {
    const keyword = lineSearch.trim().toLowerCase();

    if (!keyword) {
      return lines.slice(0, 30);
    }

    return lines
      .filter((lineName) =>
        String(lineName).toLowerCase().includes(keyword)
      )
      .slice(0, 30);
  }, [lines, lineSearch]);

  /**
   * Adds a route to the selected route chips.
   */
  const addLine = (lineName) => {
    setSelectedLines((currentLines) => {
      if (currentLines.includes(lineName)) {
        return currentLines;
      }

      return [...currentLines, lineName];
    });
  };

  /**
   * Removes a route from the selected route chips.
   */
  const removeLine = (lineName) => {
    setSelectedLines((currentLines) =>
      currentLines.filter((value) => value !== lineName)
    );
  };

  /**
   * Clears all selected routes.
   */
  const clearSelectedLines = () => {
    setSelectedLines([]);
  };

  return (
    <section className="control-panel">
      <div>
        <h1>Seoul Transit Demand Map</h1>
        <p>
          Select modes, route tags, hours, and metrics, then load aggregated demand.
        </p>
      </div>

      <div className="control-grid">
        <label>
          Mode
          <select value={mode} onChange={handleModeChange}>
            <option value="subway">Subway</option>
            <option value="bus">Bus</option>
          </select>
        </label>

        <label>
          Day Type Preset
          <select
            value={dayTypePreset}
            onChange={handleDayTypePresetChange}
          >
            {DAY_TYPE_PRESETS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label>
          Day Aggregation
          <select
            value={dayAggregation}
            onChange={(event) => setDayAggregation(event.target.value)}
          >
            {DAY_AGGREGATION_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <fieldset
          style={{
            border: "1px solid #dddddd",
            borderRadius: "8px",
            padding: "8px",
            margin: 0
          }}
        >
          <legend>Custom day types</legend>
          <div
            style={{
              display: "flex",
              gap: "8px",
              flexWrap: "wrap"
            }}
          >
            {DAY_TYPE_OPTIONS.map((option) => (
              <label
                key={option.value}
                style={{
                  display: "flex",
                  gap: "4px",
                  alignItems: "center"
                }}
              >
                <input
                  type="checkbox"
                  checked={selectedDayTypes.includes(option.value)}
                  onChange={() => toggleDayType(option.value)}
                />
                {option.label}
              </label>
            ))}
          </div>
        </fieldset>

        <label>
          Start Hour: {String(startHour).padStart(2, "0")}:00
          <input
            type="range"
            min="0"
            max="23"
            value={startHour}
            onChange={(event) => setStartHour(Number(event.target.value))}
          />
        </label>

        <label>
          End Hour: {String(endHour).padStart(2, "0")}:00
          <input
            type="range"
            min="0"
            max="23"
            value={endHour}
            onChange={(event) => setEndHour(Number(event.target.value))}
          />
        </label>

        <label>
          Metric
          <select
            value={metric}
            onChange={(event) => setMetric(event.target.value)}
          >
            {METRIC_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label>
          Map tile
          <select
            value={selectedTileLayer}
            onChange={(event) => setSelectedTileLayer(event.target.value)}
          >
            {TILE_LAYER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label
          style={{
            display: "flex",
            gap: "8px",
            alignItems: "center"
          }}
        >
          <input
            type="checkbox"
            checked={showAdminBoundary}
            onChange={(event) => setShowAdminBoundary(event.target.checked)}
          />
          Show administrative boundary
        </label>
      </div>

      <section
        className="route-tag-selector"
        style={{
          marginTop: "12px",
          padding: "12px",
          border: "1px solid #dddddd",
          borderRadius: "8px",
          backgroundColor: "#fafafa"
        }}
      >
        <div
          style={{
            display: "flex",
            gap: "8px",
            alignItems: "center",
            flexWrap: "wrap"
          }}
        >
          <label>
            Route Search
            <input
              type="text"
              value={lineSearch}
              onChange={(event) => setLineSearch(event.target.value)}
              placeholder={mode === "subway" ? "e.g. 2호선" : "e.g. 741"}
              disabled={lineLoading}
              style={{
                marginLeft: "8px",
                padding: "6px 8px"
              }}
            />
          </label>

          <button
            type="button"
            onClick={clearSelectedLines}
            disabled={selectedLines.length === 0}
          >
            Clear routes
          </button>
        </div>

        <div
          style={{
            display: "flex",
            gap: "6px",
            flexWrap: "wrap",
            marginTop: "10px"
          }}
        >
          {selectedLines.map((lineName) => (
            <button
              key={lineName}
              type="button"
              onClick={() => removeLine(lineName)}
              title="Remove selected route"
              style={{
                border: "1px solid #999999",
                borderRadius: "999px",
                padding: "4px 10px",
                backgroundColor: "#ffffff",
                cursor: "pointer"
              }}
            >
              {lineName} ×
            </button>
          ))}
        </div>

        <div
          style={{
            display: "flex",
            gap: "6px",
            flexWrap: "wrap",
            marginTop: "10px",
            maxHeight: "120px",
            overflowY: "auto"
          }}
        >
          {filteredLines.map((lineName) => {
            const selected = selectedLines.includes(lineName);

            return (
              <button
                key={lineName}
                type="button"
                onClick={() => addLine(lineName)}
                disabled={selected}
                style={{
                  border: "1px solid #cccccc",
                  borderRadius: "999px",
                  padding: "4px 10px",
                  backgroundColor: selected ? "#eeeeee" : "#ffffff",
                  cursor: selected ? "default" : "pointer"
                }}
              >
                {lineName}
              </button>
            );
          })}
        </div>
      </section>

      <div
        className="status-row"
        style={{
          marginTop: "12px",
          display: "flex",
          gap: "12px",
          alignItems: "center",
          flexWrap: "wrap"
        }}
      >
        <button
          type="button"
          onClick={onLoadDemand}
          disabled={
            selectedLines.length === 0 ||
            selectedDayTypes.length === 0 ||
            lineLoading
          }
        >
          Load demand
        </button>

        <span>
          {lineLoading && "Loading lines..."}
          {!lineLoading && lineError && lineError}
          {!lineLoading && !lineError && hasAppliedFilters &&
            "Demand layer loaded. Change filters and click Load again."}
          {!lineLoading && !lineError && !hasAppliedFilters &&
            "No demand layer loaded yet."}
        </span>

        <span>{selectedLines.length.toLocaleString()} selected route(s)</span>
        <span>{selectedDayTypes.length.toLocaleString()} selected day type(s)</span>
      </div>
    </section>
  );
}
