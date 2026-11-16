import { useMemo, useState } from "react";

import { useI18n } from "../i18n/I18nProvider.jsx";
import { busRouteStyle } from "../utils/busRouteType.js";
import NodeSearchPanel from "./NodeSearchPanel.jsx";

/** Supported day types, in week order. */
const DAY_TYPES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun_holiday"];

/** Day type presets for fast multi-day selection. */
const DAY_TYPE_PRESETS = [
  { value: "mon", dayTypes: ["mon"] },
  { value: "tue", dayTypes: ["tue"] },
  { value: "wed", dayTypes: ["wed"] },
  { value: "thu", dayTypes: ["thu"] },
  { value: "fri", dayTypes: ["fri"] },
  { value: "weekdays", dayTypes: ["mon", "tue", "wed", "thu", "fri"] },
  { value: "weekend", dayTypes: ["sat", "sun_holiday"] },
  { value: "all", dayTypes: DAY_TYPES },
  { value: "custom", dayTypes: [] }
];

const DAY_AGGREGATIONS = ["average", "sum"];
const METRICS = ["total", "boarding", "alighting"];
const ROUTE_LIST_LIMIT = 30;

const pad2 = (h) => String(h).padStart(2, "0");

/**
 * Filters for the demand map. It only collects the user's choices; the map
 * changes when the user presses Load demand.
 *
 * The basemap picker was removed from the screen because only OpenStreetMap
 * is left (CARTO now needs an API key); selectedTileLayer stays "osm".
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
  onSelectSearchNode
}) {
  const { t } = useI18n();
  const [subwayLineSearch, setSubwayLineSearch] = useState("");
  const [busLineSearch, setBusLineSearch] = useState("");

  const handlePresetChange = (event) => {
    const next = event.target.value;
    const preset = DAY_TYPE_PRESETS.find((item) => item.value === next);
    setDayTypePreset(next);
    if (preset && next !== "custom") {
      setSelectedDayTypes(preset.dayTypes);
    }
  };

  const toggleDayType = (dayType) => {
    setDayTypePreset("custom");
    setSelectedDayTypes((current) =>
      current.includes(dayType) ? current.filter((v) => v !== dayType) : [...current, dayType]
    );
  };

  const filterLines = (lines, keyword) => {
    const k = keyword.trim().toLowerCase();
    const matched = k ? lines.filter((name) => String(name).toLowerCase().includes(k)) : lines;
    return matched.slice(0, ROUTE_LIST_LIMIT);
  };

  const filteredSubwayLines = useMemo(
    () => filterLines(subwayLines, subwayLineSearch),
    [subwayLines, subwayLineSearch]
  );
  const filteredBusLines = useMemo(() => filterLines(busLines, busLineSearch), [busLines, busLineSearch]);

  const routeLoading = subwayLineLoading || busLineLoading;
  const selectedRouteCount = selectedSubwayLines.length + selectedBusLines.length;
  const canLoad = selectedRouteCount > 0 && selectedDayTypes.length > 0 && !routeLoading;

  return (
    <section className="filter-panel" aria-labelledby="map-title">
      <header className="filter-panel__head">
        <h1 id="map-title" className="page-title">{t("map.title")}</h1>
        <p className="note">{t("map.intro")}</p>
      </header>

      <div className="filter-grid">
        <label className="field">
          <span>{t("map.presets.label")}</span>
          <select value={dayTypePreset} onChange={handlePresetChange}>
            {DAY_TYPE_PRESETS.map((p) => (
              <option key={p.value} value={p.value}>{t(`map.presets.${p.value}`)}</option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>{t("map.aggregation.label")}</span>
          <select value={dayAggregation} onChange={(e) => setDayAggregation(e.target.value)}>
            {DAY_AGGREGATIONS.map((v) => (
              <option key={v} value={v}>{t(`map.aggregation.${v}`)}</option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>{t("map.metric.label")}</span>
          <select value={metric} onChange={(e) => setMetric(e.target.value)}>
            {METRICS.map((v) => (
              <option key={v} value={v}>{t(`map.metric.${v}`)}</option>
            ))}
          </select>
        </label>

        <label className="field field--range">
          <span className="num">{t("map.hours.start", { hour: pad2(startHour) })}</span>
          <input type="range" min="0" max="23" value={startHour} onChange={(e) => setStartHour(Number(e.target.value))} />
        </label>

        <label className="field field--range">
          <span className="num">{t("map.hours.end", { hour: pad2(endHour) })}</span>
          <input type="range" min="0" max="23" value={endHour} onChange={(e) => setEndHour(Number(e.target.value))} />
        </label>

        <label className="check">
          <input type="checkbox" checked={showAdminBoundary} onChange={(e) => setShowAdminBoundary(e.target.checked)} />
          {t("map.boundary")}
        </label>
      </div>

      <fieldset className="day-chips">
        <legend>{t("map.customDays")}</legend>
        {DAY_TYPES.map((d) => (
          <button
            key={d}
            type="button"
            className="chip"
            aria-pressed={selectedDayTypes.includes(d)}
            onClick={() => toggleDayType(d)}
          >
            {t(`map.days.${d}`)}
          </button>
        ))}
      </fieldset>

      <NodeSearchPanel onSelectNode={onSelectSearchNode} />

      <div className="sub-card">
        <h2 className="section-title">{t("map.districtTitle")}</h2>
        <p className="note">{t("map.districtNote")}</p>
      </div>

      <RouteSelector
        title={t("map.routes.subway")}
        placeholder={t("map.routes.subwayPlaceholder")}
        search={subwayLineSearch}
        setSearch={setSubwayLineSearch}
        loading={subwayLineLoading}
        error={subwayLineError}
        lines={filteredSubwayLines}
        selected={selectedSubwayLines}
        setSelected={setSelectedSubwayLines}
      />

      <RouteSelector
        title={t("map.routes.bus")}
        placeholder={t("map.routes.busPlaceholder")}
        search={busLineSearch}
        setSearch={setBusLineSearch}
        loading={busLineLoading}
        error={busLineError}
        lines={filteredBusLines}
        selected={selectedBusLines}
        setSelected={setSelectedBusLines}
        styleFor={(name) => busRouteStyle(name)}
      />

      <div className="load-row">
        <button type="button" className="button button--primary" onClick={onLoadDemand} disabled={!canLoad}>
          {t("map.load")}
        </button>
        <span role="status" className={filterError ? "field-error" : "note"}>
          {filterError
            ? t(`map.error.${filterError}`)
            : hasAppliedFilters
              ? t("map.status.loaded")
              : t("map.status.empty")}
        </span>
        <span className="note num">
          {t("map.counts", {
            subway: selectedSubwayLines.length,
            bus: selectedBusLines.length,
            days: selectedDayTypes.length
          })}
        </span>
      </div>
    </section>
  );
}

/** One route picker: search box, chosen routes, and up to 30 matches. */
function RouteSelector({ title, placeholder, search, setSearch, loading, error, lines, selected, setSelected, styleFor }) {
  const { t } = useI18n();
  const add = (name) => setSelected((current) => (current.includes(name) ? current : [...current, name]));
  const remove = (name) => setSelected((current) => current.filter((v) => v !== name));

  return (
    <section className="sub-card">
      <div className="route-picker__head">
        <h2 className="section-title">{title}</h2>
        <label className="field field--inline">
          <span>{t("map.routes.search")}</span>
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={placeholder}
            disabled={loading}
          />
        </label>
        <button type="button" className="button" onClick={() => setSelected([])} disabled={selected.length === 0}>
          {t("map.routes.clear")}
        </button>
        {loading && <span className="note">{t("map.routes.loading")}</span>}
        {!loading && error && <span className="field-error">{error}</span>}
      </div>

      {selected.length > 0 && (
        <div className="chip-row">
          {selected.map((name) => {
            const style = styleFor ? styleFor(name) : null;
            return (
              <button
                key={name}
                type="button"
                className="route-chip route-chip--selected"
                onClick={() => remove(name)}
                aria-label={t("map.routes.remove", { name })}
                style={style ? { background: style.bg, borderColor: style.bg, color: style.fg } : undefined}
              >
                {name} <span aria-hidden="true">×</span>
              </button>
            );
          })}
        </div>
      )}

      <div className="chip-row chip-row--scroll">
        {lines.map((name) => {
          const isSelected = selected.includes(name);
          return (
            <button key={name} type="button" className="route-chip" onClick={() => add(name)} disabled={isSelected}>
              {styleFor && <i className="route-chip__dot" style={{ background: styleFor(name).bg }} aria-hidden="true" />}
              {name}
            </button>
          );
        })}
      </div>
    </section>
  );
}
