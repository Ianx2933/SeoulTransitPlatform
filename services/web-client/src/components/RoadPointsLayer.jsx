import { CircleMarker, Tooltip } from "react-leaflet";

import { useI18n } from "../i18n/I18nProvider.jsx";

const ORIGIN = "#3d5bab";
const DESTINATION = "#d73027";

/** Circle radius 3..16 px by the square root of the riders. */
function radius(riders, max) {
  if (!(max > 0)) return 4;
  return 3 + 13 * Math.sqrt(Math.max(0, riders) / max);
}

/**
 * Where the riders of a stop or a segment board (blue) and alight (red). With
 * show = "origins" or "destinations" only that side is drawn.
 */
export default function RoadPointsLayer({ result, show = "both" }) {
  const { tx, lang } = useI18n();
  if (!result) return null;
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const sides = [];
  if (show !== "destinations") sides.push(["o", result.origins || [], ORIGIN, "Boarded here: {riders}"]);
  if (show !== "origins") sides.push(["d", result.destinations || [], DESTINATION, "Got off here: {riders}"]);
  const max = Math.max(0, ...sides.flatMap(([, pts]) => pts.map((p) => p.riders)));
  return sides.flatMap(([side, pts, color, label]) =>
    pts.map((p) => (
      <CircleMarker
        key={`${side}-${p.ars}`}
        center={[p.lat, p.lng]}
        radius={radius(p.riders, max)}
        pathOptions={{ color, weight: 1, fillColor: color, fillOpacity: 0.55 }}
      >
        <Tooltip>
          {p.name} ({p.ars})
          <br />
          {tx(label, { riders: fmt(p.riders) })}
        </Tooltip>
      </CircleMarker>
    ))
  );
}
