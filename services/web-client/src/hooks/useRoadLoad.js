import { useEffect, useState } from "react";

import { fetchRoadLoad, roadLoadKey } from "../api/roadLoadApi.js";

/**
 * Loads one road-load request (or nothing for null). The result is kept with
 * the key of the request it answers, so an answer for an old request is never
 * shown for a new one.
 */
export function useRoadLoad(request) {
  const key = roadLoadKey(request);
  const [answer, setAnswer] = useState(null);
  const [state, setState] = useState("idle");
  const [error, setError] = useState("");

  useEffect(() => {
    if (!request) {
      setState("idle");
      setError("");
      return undefined;
    }
    let ignore = false;
    setState("loading");
    setError("");
    fetchRoadLoad(request)
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return { result: answer && answer.key === key ? answer.result : null, state, error };
}
