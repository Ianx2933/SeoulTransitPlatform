import { useCallback, useMemo, useState } from "react";

import { fixedScaleMax } from "../utils/basis.js";
import { DEFAULT_MIN_LOAD, styleSegments } from "../utils/routeLoad.js";
import { useRoadLoad } from "./useRoadLoad.js";

/**
 * State of the demand map's road layer, in one place:
 *   - routeLoad: { result, minLoad, routes, basis, fixedMax } handed up by the
 *     district panel's road view (setRouteLoad), or null
 *   - boundariesOn: whether district fills stay under the lines
 *   - explore: a stop or segment look-up (select link) and its loaded result
 *   - styled: what the map draws, the look-up when there is one, else the
 *     district result with its minimum and ticked routes
 */
export function useRoadLayer() {
  const [routeLoad, setRouteLoadState] = useState(null);
  const [boundariesOn, setBoundariesOn] = useState(true);
  const [explore, setExplore] = useState(null);
  // A segment look-up reads the same months and hours as the district view.
  const exploreLoad = useRoadLoad(explore);
  const basis = routeLoad?.basis || null;

  const setRouteLoad = useCallback((value) => setRouteLoadState(value), []);
  const exploreSegment = useCallback(
    (s) => setExplore({ kind: "segment", from: s.fromArs, to: s.toArs, dayType: routeLoad?.result?.dayType, basis }),
    [routeLoad, basis]
  );
  const exploreStop = useCallback(
    (ars, direction, dayType) => setExplore({ kind: "stop", ars, direction, dayType }),
    []
  );
  const closeExplore = useCallback(() => setExplore(null), []);

  const minLoad = routeLoad?.minLoad ?? DEFAULT_MIN_LOAD;
  const styled = useMemo(() => {
    if (exploreLoad.result) {
      return styleSegments(exploreLoad.result.segments, {
        minLoad,
        fixedMax: fixedScaleMax(exploreLoad.result, explore?.basis)
      });
    }
    return routeLoad
      ? styleSegments(routeLoad.result.segments, {
          minLoad: routeLoad.minLoad,
          routes: routeLoad.routes,
          fixedMax: routeLoad.fixedMax
        })
      : null;
  }, [routeLoad, exploreLoad.result, explore, minLoad]);

  return {
    routeLoad,
    setRouteLoad,
    boundariesOn,
    setBoundariesOn,
    explore,
    exploreLoad,
    exploreSegment,
    exploreStop,
    closeExplore,
    styled,
    minLoad,
    /** Boarding hours of what is drawn (the look-up's, else the district view's). */
    hours: (exploreLoad.result ? explore?.basis?.hours : basis?.hours) || null,
    /** Result the layer fits the map to: the look-up, else the district result. */
    shownResult: exploreLoad.result || routeLoad?.result || null
  };
}
