import { useCallback, useEffect, useState } from "react";

import DataCoverageNotice from "./components/DataCoverageNotice.jsx";
import DemandControlPanel from "./components/DemandControlPanel.jsx";
import TransitDemandMap from "./components/TransitDemandMap.jsx";

/**
 * Root application component.
 *
 * Reactivity model:
 *   - Draft state lives in the control panel (route checkboxes, hour sliders,
 *     day type presets, metric, etc.).
 *   - The map and the node analysis panel both read from `appliedFilters`,
 *     which is set only when the user clicks "Load demand".
 *   - This keeps the map markers, the demand summary, and the node detail
 *     panel synchronized — the user always sees one consistent cross-section
 *     of the data, instead of the map and panel disagreeing about which
 *     hour / day type they belong to.
 */
export default function App() {
  const [selectedSubwayLines, setSelectedSubwayLines] = useState([]);
  const [selectedBusLines, setSelectedBusLines] = useState([]);
  const [dayTypePreset, setDayTypePreset] = useState("mon");
  const [selectedDayTypes, setSelectedDayTypes] = useState(["mon"]);
  const [dayAggregation, setDayAggregation] = useState("average");
  const [startHour, setStartHour] = useState(8);
  const [endHour, setEndHour] = useState(8);
  const [metric, setMetric] = useState("total");

  const [subwayLines, setSubwayLines] = useState([]);
  const [busLines, setBusLines] = useState([]);
  const [subwayLineLoading, setSubwayLineLoading] = useState(false);
  const [busLineLoading, setBusLineLoading] = useState(false);
  const [subwayLineError, setSubwayLineError] = useState("");
  const [busLineError, setBusLineError] = useState("");
  const [filterError, setFilterError] = useState("");

  const [appliedFilters, setAppliedFilters] = useState(null);
  const [selectedSearchNode, setSelectedSearchNode] = useState(null);

  const [showAdminBoundary, setShowAdminBoundary] = useState(true);
  const [selectedTileLayer, setSelectedTileLayer] = useState("cartoLight");

  /**
   * Controls whether catchment nearby nodes are drawn on the map as
   * lightweight outline markers (B-2 mode).
   */
  const [showCatchmentMarkers, setShowCatchmentMarkers] = useState(true);

  /**
   * Loads route candidates for one transport mode.
   */
  const loadLinesByMode = useCallback(
    async ({ mode, setLines, setLoading, setError }) => {
      setLoading(true);
      setError("");
      try {
        const response = await fetch(`/api/map/lines?mode=${mode}`);
        if (!response.ok) {
          throw new Error(`Failed to fetch ${mode} lines: ${response.status}`);
        }
        setLines(await response.json());
      } catch (error) {
        setError(error.message);
        setLines([]);
      } finally {
        setLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    loadLinesByMode({
      mode: "subway",
      setLines: setSubwayLines,
      setLoading: setSubwayLineLoading,
      setError: setSubwayLineError
    });
    loadLinesByMode({
      mode: "bus",
      setLines: setBusLines,
      setLoading: setBusLineLoading,
      setError: setBusLineError
    });
  }, [loadLinesByMode]);

  /**
   * Builds a continuous hour list from the selected range.
   */
  const buildSelectedHours = () => {
    const start = Math.min(startHour, endHour);
    const end = Math.max(startHour, endHour);
    return Array.from({ length: end - start + 1 }, (_, index) => start + index);
  };

  /**
   * Applies the current draft filters to the map and node analysis.
   */
  const handleLoadDemand = () => {
    if (selectedSubwayLines.length === 0 && selectedBusLines.length === 0) {
      setFilterError(
        "Please select at least one subway or bus route before loading demand."
      );
      return;
    }
    if (selectedDayTypes.length === 0) {
      setFilterError("Please select at least one day type before loading demand.");
      return;
    }
    setFilterError("");
    setAppliedFilters({
      selectedSubwayLines: [...selectedSubwayLines],
      selectedBusLines: [...selectedBusLines],
      dayTypes: [...selectedDayTypes],
      dayAggregation,
      hours: buildSelectedHours(),
      metric,
      autoApplied: false
    });
  };

  /**
   * Stores a searched node as the current node-analysis target.
   *
   * If no applied filters exist yet (the user has not clicked "Load demand"),
   * the current draft filters are auto-applied so node analysis can run
   * immediately. The map demand layer stays empty in that case because no
   * routes were selected; the node detail panel still receives data because
   * node-detail and catchment APIs don't require a route selection.
   */
  const handleSelectSearchNode = (node) => {
    if (!appliedFilters) {
      if (selectedDayTypes.length === 0) {
        setFilterError(
          "Please select at least one day type before opening node analysis."
        );
        return;
      }
      setFilterError("");
      setAppliedFilters({
        selectedSubwayLines: [...selectedSubwayLines],
        selectedBusLines: [...selectedBusLines],
        dayTypes: [...selectedDayTypes],
        dayAggregation,
        hours: buildSelectedHours(),
        metric,
        autoApplied: true
      });
    }
    setSelectedSearchNode({ ...node, source: "node-search" });
  };

  /**
   * Clears the search-driven node selection.
   *
   * Called by the map's clearNodeSelection so the search node does not
   * silently reappear when filters or radius change.
   */
  const handleClearSearchNode = () => {
    setSelectedSearchNode(null);
  };

  /**
   * Adds a route discovered in node analysis to route controls.
   */
  const handleAddRouteFromNode = (route) => {
    if (!route?.mode || !route?.serviceId) {
      return;
    }
    if (route.mode === "subway") {
      setSelectedSubwayLines((current) =>
        current.includes(route.serviceId)
          ? current
          : [...current, route.serviceId]
      );
    }
    if (route.mode === "bus") {
      setSelectedBusLines((current) =>
        current.includes(route.serviceId)
          ? current
          : [...current, route.serviceId]
      );
    }
  };

  return (
    <main className="app-shell">
      <DemandControlPanel
        dayTypePreset={dayTypePreset}
        setDayTypePreset={setDayTypePreset}
        selectedDayTypes={selectedDayTypes}
        setSelectedDayTypes={setSelectedDayTypes}
        dayAggregation={dayAggregation}
        setDayAggregation={setDayAggregation}
        startHour={startHour}
        setStartHour={setStartHour}
        endHour={endHour}
        setEndHour={setEndHour}
        selectedSubwayLines={selectedSubwayLines}
        setSelectedSubwayLines={setSelectedSubwayLines}
        selectedBusLines={selectedBusLines}
        setSelectedBusLines={setSelectedBusLines}
        metric={metric}
        setMetric={setMetric}
        subwayLines={subwayLines}
        busLines={busLines}
        subwayLineLoading={subwayLineLoading}
        busLineLoading={busLineLoading}
        subwayLineError={subwayLineError}
        busLineError={busLineError}
        filterError={filterError}
        onLoadDemand={handleLoadDemand}
        hasAppliedFilters={Boolean(appliedFilters)}
        showAdminBoundary={showAdminBoundary}
        setShowAdminBoundary={setShowAdminBoundary}
        selectedTileLayer={selectedTileLayer}
        setSelectedTileLayer={setSelectedTileLayer}
        onSelectSearchNode={handleSelectSearchNode}
      />
      <DataCoverageNotice />
      <TransitDemandMap
        filters={appliedFilters}
        showAdminBoundary={showAdminBoundary}
        selectedTileLayer={selectedTileLayer}
        selectedSearchNode={selectedSearchNode}
        onClearSearchNode={handleClearSearchNode}
        selectedSubwayLines={selectedSubwayLines}
        selectedBusLines={selectedBusLines}
        onAddRouteFromNode={handleAddRouteFromNode}
        showCatchmentMarkers={showCatchmentMarkers}
        onShowCatchmentMarkersChange={setShowCatchmentMarkers}
      />
    </main>
  );
}
