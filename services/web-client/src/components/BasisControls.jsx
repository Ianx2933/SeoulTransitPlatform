import { useEffect, useRef, useState } from "react";

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
  shiftPeriod,
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
  // typical | months | day. Months are applied with a button (each new period is
  // a BigQuery query); a day is applied when it is picked from that month's
  // TOPIS days of this day type.
  const [mode, setMode] = useState(basis.period?.day ? "day" : basis.period ? "months" : "typical");
  const [draft, setDraft] = useState(basis.period && !basis.period.day ? basis.period : null);
  const [dayMonth, setDayMonth] = useState(basis.period?.day ? basis.period.from : null);
  useEffect(() => {
    if (basis.period?.day) {
      setMode("day");
      setDayMonth(basis.period.from);
    } else if (basis.period) {
      setMode("months");
      setDraft(basis.period);
    }
  }, [basis.period]);

  // Without BigQuery, fall back to the plain typical day.
  useEffect(() => {
    if (rangeError && !isPlain(basis)) onChange({ ...TYPICAL, fixed: basis.fixed });
  }, [rangeError, basis, onChange]);

  // A day belongs to one day type: a new day type drops the day and keeps the month open.
  const lastDayType = useRef(dayType);
  useEffect(() => {
    if (lastDayType.current !== dayType) {
      lastDayType.current = dayType;
      if (basis.period?.day) onChange({ ...basis, period: null });
    }
  }, [dayType, basis, onChange]);

  const periodMode = mode === "months" && Boolean(draft);
  const choosePeriod = () => {
    setMode("months");
    const next = basis.period && !basis.period.day ? basis.period : defaultPeriod(range);
    setDraft(next);
    if (basis.period?.day) onChange({ ...basis, period: null });
  };
  const chooseDay = () => {
    setMode("day");
    setDayMonth(basis.period?.from || range?.last || null);
    if (basis.period && !basis.period.day) onChange({ ...basis, period: null });
  };
  const chooseTypical = () => {
    setMode("typical");
    setDraft(null);
    if (basis.period) onChange({ ...basis, period: null });
  };
  const setDraftEnd = (end, value) => setDraft(clampPeriod({ ...draft, [end]: value }, range, end));
  const draftChanged = periodMode && (!basis.period || draft.from !== basis.period.from || draft.to !== basis.period.to);
  const applied = basis.period && !basis.period.day ? basis.period : null;
  const prevPeriod = shiftPeriod(applied, -1, range);
  const nextPeriod = shiftPeriod(applied, 1, range);
  const applyPeriod = (p) => {
    setDraft(p);
    onChange({ ...basis, period: p });
  };

  const hours = basis.hours;
  const setHours = (next) => onChange({ ...basis, hours: next });
  const prev = shiftHours(hours, -1);
  const next = shiftHours(hours, 1);

  return (
    <div className="basis-controls">
      <div role="group" aria-label={tx("Days averaged")} className="segmented segmented--fill">
        <button type="button" aria-pressed={mode === "typical"} onClick={chooseTypical}>
          {tx("Typical day (last 12 months)")}
        </button>
        <button type="button" aria-pressed={mode === "months"} disabled={!available} onClick={choosePeriod}>
          {tx("Choose months")}
        </button>
        <button type="button" aria-pressed={mode === "day"} disabled={!available} onClick={chooseDay}>
          {tx("One day")}
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
          <button
            type="button"
            className="chip"
            disabled={draft.from === draft.to && applied?.from === draft.to && applied?.to === draft.to}
            onClick={() => applyPeriod({ from: draft.to, to: draft.to })}
          >
            {tx("This month only")}
          </button>
          {applied && (
            <span className="chip-row" role="group" aria-label={tx("Move the months")}>
              <button type="button" className="chip" disabled={!prevPeriod} onClick={() => applyPeriod(prevPeriod)} aria-label={tx("Earlier months")}>
                ◀
              </button>
              <button type="button" className="chip" disabled={!nextPeriod} onClick={() => applyPeriod(nextPeriod)} aria-label={tx("Later months")}>
                ▶
              </button>
            </span>
          )}
          <span className="note">{tx("Whole months, up to {max}.", { max: MAX_MONTHS })}</span>
        </form>
      )}

      {mode === "day" && available && dayMonth && (
        <DayPicker
          months={months}
          month={dayMonth}
          onMonth={setDayMonth}
          dayType={dayType}
          selected={basis.period?.day || null}
          onPick={(day) => onChange({ ...basis, period: { from: dayMonth, to: dayMonth, day } })}
        />
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
 * One TOPIS day: a month, then one of the days TOPIS published in it for this
 * day type (about one a month for each type). Special days left out of every
 * mean (a strike, Seollal) can be chosen too, marked as such: one day's own
 * figures are worth seeing.
 */
function DayPicker({ months, month, onMonth, dayType, selected, onPick }) {
  const { tx } = useI18n();
  const [days, setDays] = useState(null);
  useEffect(() => {
    setDays(null);
    let ignore = false;
    fetchPeriodDays({ period: { from: month, to: month }, dayType })
      .then((d) => {
        if (ignore) return;
        const normal = (d.days || []).map((day) => ({ day: String(day), special: false }));
        const special = (d.excluded || []).map((e) => ({ day: String(e.date), special: true }));
        setDays([...normal, ...special].sort((a, b) => a.day.localeCompare(b.day)));
      })
      .catch(() => {
        if (!ignore) setDays([]);
      });
    return () => {
      ignore = true;
    };
  }, [month, dayType]);
  return (
    <div className="road-controls">
      <label className="field-inline">
        {tx("Month")}
        <select value={month} onChange={(e) => onMonth(e.target.value)}>
          {months.map((m) => (
            <option key={m} value={m}>{m}</option>
          ))}
        </select>
      </label>
      {days === null ? (
        <span className="note">{tx("Loading days…")}</span>
      ) : days.length === 0 ? (
        <span className="note">{tx("No TOPIS day of this type in {month}.", { month })}</span>
      ) : (
        <span className="chip-row" role="group" aria-label={tx("TOPIS days")}>
          {days.map(({ day, special }) => (
            <button
              key={day}
              type="button"
              className="chip"
              aria-pressed={selected === day}
              onClick={() => onPick(day)}
              title={special ? tx("Left out of every mean; shown as that day") : undefined}
            >
              {day.slice(5)}
              {special && ` ${tx("(special day)")}`}
            </button>
          ))}
        </span>
      )}
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
  const oneDay = period?.day || null;
  const from = oneDay ? null : period?.from;
  const to = oneDay ? null : period?.to;

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
  if (oneDay) {
    text = tx("{day}: that one day as TOPIS published it, not a mean.", { day: oneDay });
  } else if (!period) {
    text = tx("Typical day: the mean of the days of this type in the last twelve months.");
  } else if (!days) {
    text = tx("Mean of {period}.", { period: label });
  } else {
    // Days left out of means (strikes, Seollal, Chuseok) are not listed; they stay choosable as one day.
    text =
      days.nDays === 0
        ? tx("No usual days of this type in {period}; choose one day to see the published days.", { period: label })
        : tx("Mean of {period}.", { period: label });
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
