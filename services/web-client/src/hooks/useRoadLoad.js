import { useEffect, useRef, useState } from "react";

import { fetchRoadLoad, roadLoadKey } from "../api/roadLoadApi.js";

/**
 * Loads one road-load request (or nothing for null). The effect runs when the
 * request's key changes, not when the caller builds an equal object again;
 * the request itself is read from a ref. The result is kept with its key, so an
 * answer for an old request is never shown for a new one.
 */
export function useRoadLoad(request) {
  const key = roadLoadKey(request);
  const requestRef = useRef(request);
  requestRef.current = request;
  const [answer, setAnswer] = useState(null);
  const [state, setState] = useState("idle");
  const [error, setError] = useState("");

  useEffect(() => {
    const current = requestRef.current;
    if (!current) {
      setState("idle");
      setError("");
      return undefined;
    }
    let ignore = false;
    setState("loading");
    setError("");
    fetchRoadLoad(current)
      .then((result) => {
        if (ignore) return;
        setAnswer({ key, result });
        setState("done");
      })
      .catch((e) => {
        if (ignore) return;
        setError(e.message);
        setState("error");
      });
    return () => {
      ignore = true;
    };
  }, [key]);

  return { result: answer && answer.key === key ? answer.result : null, state, error };
}
