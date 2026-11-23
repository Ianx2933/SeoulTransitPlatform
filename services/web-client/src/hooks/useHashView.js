import { useCallback, useEffect, useState } from "react";

export const VIEWS = ["map", "route", "method", "download", "about", "privacy"];
const DEFAULT_VIEW = "map";

/** The view named in a location hash such as "#/route", or the default. */
export function viewFromHash(hash) {
  const name = String(hash || "").replace(/^#\/?/, "").split(/[/?]/)[0];
  return VIEWS.includes(name) ? name : DEFAULT_VIEW;
}

/**
 * Current view kept in the URL hash, so a view can be bookmarked and the back
 * button works, without a router dependency.
 */
export function useHashView() {
  const [view, setViewState] = useState(() => viewFromHash(window.location.hash));

  useEffect(() => {
    const onChange = () => setViewState(viewFromHash(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);

  const setView = useCallback((next) => {
    window.location.hash = `#/${next}`;
  }, []);

  return [view, setView];
}
