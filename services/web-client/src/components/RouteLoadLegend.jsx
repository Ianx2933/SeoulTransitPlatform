import { useI18n } from "../i18n/I18nProvider.jsx";
import { hoursRange, hoursWidth } from "../utils/basis.js";
import { legendSteps } from "../utils/routeLoad.js";

/**
 * Scale for the road layer: each colour with the riders it stands for, a day
 * or in the chosen boarding hours. A fixed scale says what it is fixed to.
 */
export default function RouteLoadLegend({ styled, minLoad, hours = null }) {
  const { tx, lang } = useI18n();
  const steps = legendSteps(styled, minLoad);
  if (steps.length === 0) return null;
  const fmt = (v) => Math.round(Number(v || 0)).toLocaleString(lang === "ko" ? "ko-KR" : "en-US");
  return (
    <figure className="load-legend">
      <figcaption className="note">
        {hours ? tx("Riders boarding {hours} per segment", { hours: hoursRange(hours) }) : tx("Riders a day per segment")}
        {styled.fixed &&
          (hoursWidth(hours) === 1
            ? ` · ${tx("fixed: up to the busiest hour of the day")}`
            : ` · ${tx("fixed: up to {n} × the busiest hour of the day", { n: hoursWidth(hours) })}`)}
      </figcaption>
      <ol className="load-legend__steps">
        {steps.map((s) => (
          <li key={s.color}>
            <i style={{ background: s.color }} />
            <span className="num">{`${fmt(s.from)}–${fmt(s.to)}`}</span>
          </li>
        ))}
      </ol>
    </figure>
  );
}
