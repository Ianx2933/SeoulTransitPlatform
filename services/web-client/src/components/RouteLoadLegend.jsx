import { useI18n } from "../i18n/I18nProvider.jsx";
import { legendSteps } from "../utils/routeLoad.js";

/** Scale for the road layer: each colour with the riders a day it stands for. */
export default function RouteLoadLegend({ styled, minLoad }) {
  const { tx, lang } = useI18n();
  const steps = legendSteps(styled, minLoad);
  if (steps.length === 0) return null;
  const fmt = (v) => Math.round(Number(v || 0)).toLocaleString(lang === "ko" ? "ko-KR" : "en-US");
  return (
    <figure className="load-legend">
      <figcaption className="note">{tx("Riders a day per segment")}</figcaption>
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
