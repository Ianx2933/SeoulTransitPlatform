import { useEffect, useState } from "react";

const URL = "/data/capital_area_admin_dong_4326.geojson";
let cache = null;

/**
 * The capital-area 행정동 boundaries (the same file the demand map draws),
 * fetched once when first wanted and kept for the session. null until loaded
 * or while not wanted.
 */
export function useDistrictBoundaries(wanted) {
  const [data, setData] = useState(cache);
  useEffect(() => {
    if (!wanted || cache) return undefined;
    let ignore = false;
    fetch(URL)
      .then((r) => (r.ok ? r.json() : null))
      .then((json) => {
        if (!json) return;
        cache = json;
        if (!ignore) setData(json);
      })
      .catch(() => {});
    return () => {
      ignore = true;
    };
  }, [wanted]);
  return wanted ? data : null;
}
