import { useEffect, useState } from "react";

import { fetchPeriodDays, fetchPeriodRange } from "../api/periodApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import {
  clampPeriod,
  defaultPeriod,
  hoursRange,
  isPeriodUnavailable,
  isPlain,
  MAX_MONTHS,
  monthsList,
  setHoursEnd,
  shiftHours,
  TYPICAL
} from "../utils/basis.js";

const HOURS = Array.from({ length: 24 }, (_, h) => h);
const pad2 = (h) => String(h).padStart(2, "0");

/** The months with route OD, or the reason there are none (BigQuery off). */
export function usePeriodRange() {
  const [state, setState] = useState({ range: null, error: "" });
  useEffect(() => {
    let ignore = false;
    fetchPeriodRange()
      .then((range) => {
        if (!ignore) setState({ range, error: "" });
      })
      .catch((e) => {
        if (!ignore) setState({ range: null, error: e.message || "unavailable" });
      });
    return () => {
      ignore = true;
    };
  }, []);
  return state;
}

/**
 * What a view reads, besides its day type: the typical day or a period of
 * whole months (up to twelve, applied with a button because each new period
 * is a BigQuery query), and the whole day or boarding hours (one hour or a
 * range, moved with ◀ ▶). withHours false is for views that only have whole
 * days (district OD, how far); dayOnlyNote then says so. showFixed offers the
 * fixed road colours while the hours change.
 *
 * Period and hours both need BigQuery (hours use the boarding stops' hourly
 * profiles), so when /api/period/range fails they are switched off and the
 * view stays on the typical day.
 */
export default function BasisControls({ basis, onChange, dayType, withHours = true, showFixed = false, dayOnlyNote = null }) {
  const { tx } = useI18n();
  const { range, error: rangeError } = usePeriodRange();
  const available = Boolean(range);
  const months = available ? monthsList(range.first, range.last) : [];
  const [draft, setDraft] = useState(basis.period || null);
  useEffect(() => setDraft(basis.period || null), [basis.period]);

  // Without BigQuery, fall back to the plain typical day.
  useEffect(() => {
    if (rangeError && !isPlain(basis)) onChange({ ...TYPICAL, fixed: basis.fixed });
  }, [rangeError, basis, onChange]);

  const periodMode = Boolean(draft);
  const choosePeriod = () => setDraft(basis.period || defaultPeriod(range));
  const chooseTypical = () => {
    setDraft(null);
    if (basis.period) onChange({ ...basis, period: null });
  };
  const setDraftEnd = (end, value) => setDraft(clampPeriod({ ...draft, [end]: value }, range, end));
  const draftChanged = periodMode && (!basis.period || draft.from !== basis.period.from || draft.to !== basis.period.to);

  const hours = basis.hours;
  const setHours = (next) => onChange({ ...basis, hours: next });
  const prev = shiftHours(hours, -1);
  const next = shiftHours(hours, 1);

  return (
    <div className="basis-controls">
      <div role="group" aria-label={tx("Days averaged")} className="segmented segmented--fill">
        <button type="button" aria-pressed={!periodMode} onClick={chooseTypical}>
          {tx("Typical day (last 12 months)")}
        </button>
        <button type="button" aria-pressed={periodMode} disabled={!available} onClick={choosePeriod}>
          {tx("Choose months")}
        </button>
      </div>

      {periodMode && (
        <form
          className="road-controls"
          onSubmit={(e) => {
            e.preventDefault();
            if (draftChanged) onChange({ ...basis, period: draft });
          }}
        >
          <label className="field-inline">
            {tx("From")}
            <select value={draft.from} onChange={(e) => setDraftEnd("from", e.target.value)}>
              {months.map((m) => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          </label>
          <label className="field-inline">
            {tx("to")}
            <select value={draft.to} onChange={(e) => setDraftEnd("to", e.target.value)}>
              {months.map((m) => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          </label>
          <button type="submit" className="chip" disabled={!draftChanged}>{tx("Apply")}</button>
          <span className="note">{tx("Whole months, up to {max}.", { max: MAX_MONTHS })}</span>
        </form>
      )}

      {withHours ? (
        <div className="basis-hours">
          <div className="road-controls">
            <button type="button" className="chip" aria-pressed={!hours} onClick={() => setHours(null)}>
              {tx("Whole day")}
            </button>
            <label className="field-inline">
              {tx("Boarding from")}
              <select
                value={hours ? hours.from : ""}
                disabled={!available}
                onChange={(e) => setHours(setHoursEnd(hours, "from", Number(e.target.value)))}
              >
                {!hours && <option value="">--</option>}
                {HOURS.map((h) => (
                  <option key={h} value={h}>{`${pad2(h)}:00`}</option>
                ))}
              </select>
            </label>
            <label className="field-inline">
              {tx("to")}
              <select
                value={hours ? hours.to : ""}
                disabled={!available}
                onChange={(e) => setHours(setHoursEnd(hours, "to", Number(e.target.value)))}
              >
                {!hours && <option value="">--</option>}
                {HOURS.map((h) => (
                  <option key={h} value={h}>{`${pad2(h)}:59`}</option>
                ))}
              </select>
            </label>
            <span className="chip-row" role="group" aria-label={tx("Move the hours")}>
              <button type="button" className="chip" disabled={!prev} onClick={() => setHours(prev)} aria-label={tx("One hour earlier")}>
                ◀
              </button>
              <button type="button" className="chip" disabled={!next} onClick={() => setHours(next)} aria-label={tx("One hour later")}>
                ▶
              </button>
            </span>
          </div>
          {showFixed && hours && (
            <label className="check">
              <input type="checkbox" checked={basis.fixed} onChange={(e) => onChange({ ...basis, fixed: e.target.checked })} />
              {tx("Keep the road colour scale while the hours change")}
            </label>
          )}
        </div>
      ) : (
        dayOnlyNote && <p className="note">{dayOnlyNote}</p>
      )}

      {rangeError && <p className="note">{tx("Months and hours are not available right now; showing the typical day.")}</p>}
      <BasisNote basis={basis} dayType={dayType} withHours={withHours} />
    </div>
  );
}

/**
 * One sentence on what the numbers are: the typical day, or a period with
 * its sample days ("3 days: 03-11 · 04-15 · 05-13"), and the boarding hours.
 * Route OD has about one day of each day type a month, so a short period
 * rests on few days, which the sentence makes plain.
 */
export function BasisNote({ basis, dayType, withHours = true }) {
  const { tx } = useI18n();
  const [days, setDays] = useState(null);
  const period = basis?.period || null;
  const from = period?.from;
  const to = period?.to;

  useEffect(() => {
    setDays(null);
    if (!from) return undefined;
    let ignore = false;
    fetchPeriodDays({ period: { from, to }, dayType })
      .then((d) => {
        if (!ignore) setDays(d);
      })
      .catch(() => {
        if (!ignore) setDays({ nDays: null, days: [] });
      });
    return () => {
      ignore = true;
    };
  }, [from, to, dayType]);

  const label = from === to ? from : `${from}–${to}`;
  let text;
  if (!period) {
    text = tx("Typical day: the mean of the days of this type in the last twelve months.");
  } else if (!days) {
    text = tx("Mean of {period}.", { period: label });
  } else {
    const list = (days.days || []).map((d) => String(d).slice(5));
    const shown = list.length > 12 ? `${list.slice(0, 12).join(" · ")} …` : list.join(" · ");
    text =
      days.nDays === 0
        ? tx("No days of this type in {period}.", { period: label })
        : tx("Mean of {period}, {n} sample days: {days}", { period: label, n: days.nDays ?? "-", days: shown });
  }
  const hours = withHours && basis?.hours ? basis.hours : null;
  return (
    <p className="note num basis-note">
      {text}
      {hours && ` ${tx("Only riders boarding {hours}, split by each boarding stop's hourly profile.", { hours: hoursRange(hours) })}`}
    </p>
  );
}

/**
 * The API could not reach BigQuery (503): says so and offers the typical
 * day, which is served from Cloud SQL. Shows nothing for other errors.
 */
export function PeriodUnavailable({ message, basis, onTypical }) {
  const { tx } = useI18n();
  if (!isPeriodUnavailable(message) || isPlain(basis)) return null;
  return (
    <div className="error-box" role="alert">
      <p>{tx("Months and hours could not be loaded right now.")}</p>
      <button type="button" className="chip" onClick={onTypical}>
        {tx("Show the typical day")}
      </button>
    </div>
  );
}
