import { useEffect, useState } from "react";

/** Whether a CSS media query matches now, following changes (rotation, resizing). */
export function useMediaQuery(query) {
  const get = () => (typeof window !== "undefined" && window.matchMedia ? window.matchMedia(query).matches : false);
  const [matches, setMatches] = useState(get);
  useEffect(() => {
    if (!window.matchMedia) return undefined;
    const list = window.matchMedia(query);
    const onChange = () => setMatches(list.matches);
    onChange();
    list.addEventListener?.("change", onChange);
    return () => list.removeEventListener?.("change", onChange);
  }, [query]);
  return matches;
}
