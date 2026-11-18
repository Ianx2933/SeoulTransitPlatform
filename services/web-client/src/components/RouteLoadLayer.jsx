import { useEffect } from "react";
import { Polyline, Tooltip, useMap } from "react-leaflet";

import { useI18n } from "../i18n/I18nProvider.jsx";
import { loadBounds, routeBreakdown } from "../utils/routeLoad.js";

/**
 * Bus riders of the chosen area on the road (DongRouteLoadService): every
 * stop-to-stop segment they ride, summed over routes, in ten steps from green
 * to red (styleSegments). Fits the map when a new result arrives, not when
 * only the minimum or the chosen routes change. With onSegmentClick, clicking
 * a segment between two known stops asks who passes it (select link).
 */
export default function RouteLoadLayer({ result, segments, onSegmentClick }) {
  const { tx, lang } = useI18n();
  const map = useMap();

  useEffect(() => {
    const bounds = loadBounds(result?.segments);
    if (bounds) map.fitBounds(bounds, { padding: [24, 24] });
  }, [result, map]);

  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  return segments.map((s, i) => (
    <Polyline
      key={`${s.fromArs || ""}-${s.toArs || ""}-${i}`}
      positions={s.path}
      pathOptions={{ color: s.color, weight: s.weight, opacity: 0.9, lineCap: "round" }}
      eventHandlers={
        onSegmentClick && s.fromArs && s.toArs ? { click: () => onSegmentClick(s) } : undefined
      }
    >
      <Tooltip sticky>
        {(s.fromName || s.toName) && (
          <>
            <strong>{`${s.fromName || s.fromArs || "?"} → ${s.toName || s.toArs || "?"}`}</strong>
            <br />
          </>
        )}
        {tx("{riders} riders a day", { riders: fmt(s.load) })}
        <br />
        {tx("Routes: {routes}", { routes: routeBreakdown(s, fmt) })}
        {onSegmentClick && s.fromArs && s.toArs && (
          <>
            <br />
            <em>{tx("Click to see who passes this segment")}</em>
          </>
        )}
      </Tooltip>
    </Polyline>
  ));
}
