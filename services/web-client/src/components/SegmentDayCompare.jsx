import { useEffect, useState } from "react";

import { fetchRouteSegment } from "../api/routeAnalysisApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { basisKey } from "../utils/basis.js";
import { DAY_TYPES, sameEnds, segmentDayRows } from "../utils/segmentDays.js";

/**
 * The section on screen on all three day types: how many ride it on a
 * weekday, a Saturday and a Sunday or holiday, and the weekend as a share of
 * the weekday. Same months and hours as the screen.
 */
export default function SegmentDayCompare({ route, dayType, basis, segment, fmt }) {
  const { t } = useI18n();
  const key = `${route}|${segment.fromSeq}|${segment.toSeq}|${basisKey(basis)}`;
  const [state, setState] = useState({ key: null, byDay: {}, mismatch: [] });

  useEffect(() => {
    let ignore = false;
    const others = DAY_TYPES.filter((d) => d !== dayType);
    Promise.all(
      others.map((d) =>
        fetchRouteSegment({
          route,
          dayType: d,
          basis,
          from: segment.fromSeq,
          to: segment.toSeq,
          messages: { defaultMessage: "-", notFoundMessage: "-" }
        }).catch(() => null)
      )
    ).then((results) => {
      if (ignore) return;
      const byDay = { [dayType]: segment };
      const mismatch = [];
      others.forEach((d, i) => {
        const seg = results[i];
        if (seg && !sameEnds(seg, segment)) mismatch.push(d);
        byDay[d] = seg && sameEnds(seg, segment) ? seg : null;
      });
      setState({ key, byDay, mismatch });
    });
    return () => {
      ignore = true;
    };
    // key stands for route, section and basis; dayType and segment change with it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, dayType, segment]);

  if (state.key !== key) return <p className="note">{t("route.segmentDays.loading")}</p>;
  const rows = segmentDayRows(state.byDay);
  const label = (k) => (k === "total" ? t("route.segmentDays.total") : k === "routeTotal" ? t("route.segmentDays.routeTotal") : t(`route.segment.${k}`));

  return (
    <div className="segment-days">
      <p className="label-strong">{t("route.segmentDays.title")}</p>
      <table className="compare-table num">
        <thead>
          <tr>
            <th />
            {DAY_TYPES.map((d) => (
              <th key={d}>{t(`dayType.${d}`)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key} className={r.key === "total" ? "segment-days__total" : undefined}>
              <th>{label(r.key)}</th>
              {DAY_TYPES.map((d) => (
                <td key={d}>
                  {r.values[d] === null ? "–" : fmt(r.values[d])}
                  {d !== "weekday" && r.shares[d] !== null && (
                    <span className="note"> {t("route.segmentDays.share", { value: r.shares[d] })}</span>
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {state.mismatch.length > 0 && (
        <p className="note">
          {t("route.segmentDays.mismatch", { days: state.mismatch.map((d) => t(`dayType.${d}`)).join(", ") })}
        </p>
      )}
      <p className="note">{t("route.segmentDays.note")}</p>
    </div>
  );
}
