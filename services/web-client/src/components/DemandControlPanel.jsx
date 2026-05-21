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
 * Control panel for multi-mode demand exploration.

 * This component is responsible for letting the user select filters and Load demand data

 * It only lets the user select filters and explicitly request loading.
 */
export default function DemandControlPanel({
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
  selectedSubwayLines,
  setSelectedSubwayLines,
  selectedBusLines,
  setSelectedBusLines,
  metric,
  setMetric,
  subwayLines = [],
  busLines = [],
  subwayLineLoading,
  busLineLoading,
  subwayLineError,
  busLineError,
  filterError,
  onLoadDemand,
  hasAppliedFilters,
  showAdminBoundary,
  setShowAdminBoundary,
  selectedTileLayer,
  setSelectedTileLayer
}) {
  /**
   * Local search keywords for each route selector.
   */
  const [subwayLineSearch, setSubwayLineSearch] = useState("");
  const [busLineSearch, setBusLineSearch] = useState("");

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
   * (사용자 검색어로 노선 후보를 필터링합니다.)
   */
  const filterLines = (lines, keyword) => {
    const normalizedKeyword = keyword.trim().toLowerCase();

    if (!normalizedKeyword) {
      return lines.slice(0, 30);
    }

    return lines
      .filter((lineName) =>
        String(lineName).toLowerCase().includes(normalizedKeyword)
      )
      .slice(0, 30);
  };

  const filteredSubwayLines = useMemo(() => {
    return filterLines(subwayLines, subwayLineSearch);
  }, [subwayLines, subwayLineSearch]);

  const filteredBusLines = useMemo(() => {
    return filterLines(busLines, busLineSearch);
  }, [busLines, busLineSearch]);

  /**
   * Adds a route to the selected route chips.
   */
  const addLine = (lineName, selectedLines, setSelectedLines) => {
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
  const removeLine = (lineName, setSelectedLines) => {
    setSelectedLines((currentLines) =>
      currentLines.filter((value) => value !== lineName)
    );
  };

  /**
   * Renders one route selector section.
   */
  const renderRouteSelector = ({
    title,
    searchValue,
    setSearchValue,
    searchPlaceholder,
    loading,
    error,
    lines,
    selectedLines,
    setSelectedLines
  }) => {
    return (
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
          <strong>{title}</strong>

          <label>
            Route Search
            <input
              type="text"
              value={searchValue}
              onChange={(event) => setSearchValue(event.target.value)}
              placeholder={searchPlaceholder}
              disabled={loading}
              style={{
                marginLeft: "8px",
                padding: "6px 8px"
              }}
            />
          </label>

          <button
            type="button"
            onClick={() => setSelectedLines([])}
            disabled={selectedLines.length === 0}
          >
            Clear routes
          </button>

          {loading && <span>Loading routes...</span>}
          {!loading && error && <span>{error}</span>}
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
              onClick={() => removeLine(lineName, setSelectedLines)}
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
          {lines.map((lineName) => {
            const selected = selectedLines.includes(lineName);

            return (
              <button
                key={lineName}
                type="button"
                onClick={() =>
                  addLine(lineName, selectedLines, setSelectedLines)
                }
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
    );
  };

  const routeLoading = subwayLineLoading || busLineLoading;
  const selectedRouteCount =
    selectedSubwayLines.length + selectedBusLines.length;

  return (
    <section className="control-panel">
      <div>
        <h1>Seoul Transit Demand Map</h1>
        <p>
          Select subway routes, bus routes, hours, and metrics, then load aggregated demand.
        </p>
      </div>

      <div className="control-grid">
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

      {renderRouteSelector({
        title: "Subway routes",
        searchValue: subwayLineSearch,
        setSearchValue: setSubwayLineSearch,
        searchPlaceholder: "e.g. 2호선",
        loading: subwayLineLoading,
        error: subwayLineError,
        lines: filteredSubwayLines,
        selectedLines: selectedSubwayLines,
        setSelectedLines: setSelectedSubwayLines
      })}

      {renderRouteSelector({
        title: "Bus routes",
        searchValue: busLineSearch,
        setSearchValue: setBusLineSearch,
        searchPlaceholder: "e.g. 741 or 9401",
        loading: busLineLoading,
        error: busLineError,
        lines: filteredBusLines,
        selectedLines: selectedBusLines,
        setSelectedLines: setSelectedBusLines
      })}

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
            selectedRouteCount === 0 ||
            selectedDayTypes.length === 0 ||
            routeLoading
          }
        >
          Load demand
        </button>

        <span>
          {filterError && filterError}
          {!filterError && hasAppliedFilters &&
            "Demand layer loaded. Change filters and click Load again."}
          {!filterError && !hasAppliedFilters &&
            "No demand layer loaded yet."}
        </span>

        <span>{selectedSubwayLines.length.toLocaleString()} selected subway route(s)</span>
        <span>{selectedBusLines.length.toLocaleString()} selected bus route(s)</span>
        <span>{selectedDayTypes.length.toLocaleString()} selected day type(s)</span>
      </div>
    </section>
  );
}
