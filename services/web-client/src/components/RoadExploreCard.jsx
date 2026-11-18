import { useI18n } from "../i18n/I18nProvider.jsx";
import { csvFileName } from "../utils/roadCsv.js";
import CsvButton from "./CsvButton.jsx";

/**
 * Summary of a stop or segment look-up (select link): who they are, where
 * they board and where they get off. onClose ends the look-up.
 */
export default function RoadExploreCard({ request, state, error, result, onClose }) {
  const { tx, lang } = useI18n();
  if (!request) return null;
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const alighting = request.kind === "stop" && request.direction === "alight";
  const heading =
    request.kind === "segment"
      ? tx("Riders passing this segment")
      : request.direction === "alight"
        ? tx("Riders getting off at this stop")
        : tx("Riders boarding at this stop");
  return (
    <section className="side-card road-explore" aria-live="polite">
      <div className="road-explore__head">
        <h2 className="section-title">{heading}</h2>
        <button type="button" className="chip" onClick={onClose}>{tx("Close")}</button>
      </div>
      {state === "loading" && <p className="note">{tx("Loading…")}</p>}
      {state === "error" && <p className="field-error">{error}</p>}
      {result && (
        <>
          <p className="label-strong">{result.title}</p>
          <p className="note num">
            {tx("{riders} riders a day on {count} routes", {
              riders: fmt(result.placedPassengers),
              count: (result.routes || []).length
            })}
          </p>
          <p className="note">{tx("Blue circles: where they board. Red circles: where they get off. Lines: the road they ride.")}</p>
          <CsvButton
            result={result}
            fileName={csvFileName(request.kind, request.kind === "segment" ? `${request.from}_${request.to}` : request.ars, request.direction, result.dayType)}
          />
          <div className="two-col">
            {request.kind === "segment" && (
              <div>
                <h3 className="flow-title">{tx("Where they board")}</h3>
                <ol className="flow-list">
                  {(result.origins || []).slice(0, 8).map((p) => (
                    <li key={p.ars}>
                      <span>{p.name}</span>
                      <span className="num">{fmt(p.riders)}</span>
                    </li>
                  ))}
                </ol>
              </div>
            )}
            <div>
              <h3 className="flow-title">{alighting ? tx("Where they board") : tx("Where they get off")}</h3>
              <ol className="flow-list">
                {((alighting ? result.origins : result.destinations) || []).slice(0, 8).map((p) => (
                  <li key={p.ars}>
                    <span>{p.name}</span>
                    <span className="num">{fmt(p.riders)}</span>
                  </li>
                ))}
              </ol>
            </div>
          </div>
        </>
      )}
    </section>
  );
}
