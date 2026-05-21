import { useEffect, useState } from "react";
import TransitDemandMap from "./components/TransitDemandMap.jsx";
import DemandControlPanel from "./components/DemandControlPanel.jsx";
import DataCoverageNotice from "./components/DataCoverageNotice.jsx";

/**
 * Root application component that separates draft filters from applied fileters.
 *
 * Draft filters are edited by the user.
 *
 * Applied filters are sent to the map only after the user clicks Load.
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

  /**
   * Controls whether administrative district boundaries are shown on the map.
   */
  const [showAdminBoundary, setShowAdminBoundary] = useState(true);

  /**
   * Stores the selected Leaflet tile layer.
   *
   * CartoDB Positron is the default because it works well as a quiet analytical basemap.
   */
  const [selectedTileLayer, setSelectedTileLayer] = useState("cartoLight");

  /**
   * Loads route candidates for one transport mode.
   */
  const loadLinesByMode = async ({
    mode,
    setLines,
    setLoading,
    setError
  }) => {
    setLoading(true);
    setError("");

    try {
      const response = await fetch(`/api/map/lines?mode=${mode}`);

      if (!response.ok) {
        throw new Error(`Failed to fetch ${mode} lines: ${response.status}`);
      }

      const data = await response.json();
      setLines(data);
    } catch (error) {
      setError(error.message);
      setLines([]);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Loads subway and bus line lists once when the dashboard starts.
   */
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
  }, []);

  /**
   * Builds a continuous hour list from the selected range.
   */
  const buildSelectedHours = () => {
    const start = Math.min(startHour, endHour);
    const end = Math.max(startHour, endHour);

    return Array.from(
      { length: end - start + 1 },
      (_, index) => start + index
    );
  };

  /**
   * Applies the current filter selection to the map.
   */
  const handleLoadDemand = () => {
    if (
      selectedSubwayLines.length === 0 &&
      selectedBusLines.length === 0
    ) {
      setFilterError("Please select at least one subway or bus route before loading demand.");
      return;
    }

    if (selectedDayTypes.length === 0) {
      setFilterError("Please select at least one day type before loading demand.");
      return;
    }

    setFilterError("");
    setAppliedFilters({
      selectedSubwayLines,
      selectedBusLines,
      dayTypes: selectedDayTypes,
      dayAggregation,
      hours: buildSelectedHours(),
      metric
    });
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
      />

      <DataCoverageNotice />

      <TransitDemandMap
        filters={appliedFilters}
        showAdminBoundary={showAdminBoundary}
        selectedTileLayer={selectedTileLayer}
      />
    </main>
  );
}
