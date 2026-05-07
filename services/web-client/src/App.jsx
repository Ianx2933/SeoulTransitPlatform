import { useEffect, useState } from "react";
import TransitDemandMap from "./components/TransitDemandMap.jsx";
import DemandControlPanel from "./components/DemandControlPanel.jsx";

/**
 * Root application component.
 *
 * This component separates draft filters from applied filters.
 * Draft filters are edited by the user.
 * Applied filters are sent to the map only after the user clicks Load.
 */
export default function App() {
  const [mode, setMode] = useState("subway");
  const [line, setLine] = useState("");
  const [dayType, setDayType] = useState("mon");
  const [hour, setHour] = useState(8);
  const [metric, setMetric] = useState("total");

  const [lines, setLines] = useState([]);
  const [lineLoading, setLineLoading] = useState(false);
  const [lineError, setLineError] = useState("");

  const [appliedFilters, setAppliedFilters] = useState(null);

  /**
   * Loads available lines whenever the transport mode changes.
   */
  useEffect(() => {
    let ignore = false;

    async function loadLines() {
      setLineLoading(true);
      setLineError("");
      setLines([]);
      setLine("");

      try {
        const response = await fetch(`/api/map/lines?mode=${mode}`);

        if (!response.ok) {
          throw new Error(`Failed to fetch lines: ${response.status}`);
        }

        const data = await response.json();

        if (!ignore) {
          setLines(data);
        }
      } catch (error) {
        if (!ignore) {
          setLineError(error.message);
        }
      } finally {
        if (!ignore) {
          setLineLoading(false);
        }
      }
    }

    loadLines();

    return () => {
      ignore = true;
    };
  }, [mode]);

  /**
   * Applies the current filter selection to the map.
   */
  const handleLoadDemand = () => {
    if (!line) {
      setLineError("Please select a line before loading demand.");
      return;
    }

    setLineError("");
    setAppliedFilters({
      mode,
      line,
      dayType,
      hour,
      metric
    });
  };

  return (
    <main className="app-shell">
      <DemandControlPanel
        mode={mode}
        setMode={setMode}
        dayType={dayType}
        setDayType={setDayType}
        hour={hour}
        setHour={setHour}
        line={line}
        setLine={setLine}
        metric={metric}
        setMetric={setMetric}
        lines={lines}
        lineLoading={lineLoading}
        lineError={lineError}
        onLoadDemand={handleLoadDemand}
        hasAppliedFilters={Boolean(appliedFilters)}
      />

      <TransitDemandMap filters={appliedFilters} />
    </main>
  );
}
