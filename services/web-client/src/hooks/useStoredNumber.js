import { useCallback, useState } from "react";

/** Number kept in localStorage; falls back quietly where storage is blocked. */
export function readStoredNumber(key, fallback) {
  try {
    const raw = window.localStorage.getItem(key);
    const value = raw === null ? NaN : Number(raw);
    return Number.isFinite(value) && value >= 0 ? value : fallback;
  } catch {
    return fallback;
  }
}

export function writeStoredNumber(key, value) {
  try {
    window.localStorage.setItem(key, String(value));
  } catch {
    // storage blocked (private mode, sandbox): keep the value for this page only
  }
}

/** useState for a non-negative number remembered across visits under key. */
export function useStoredNumber(key, fallback) {
  const [value, setValue] = useState(() => readStoredNumber(key, fallback));
  const set = useCallback(
    (next) => {
      setValue(next);
      writeStoredNumber(key, next);
    },
    [key]
  );
  return [value, set];
}
