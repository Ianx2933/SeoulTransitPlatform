import { useLayoutEffect, useRef } from "react";

/**
 * A ref that always holds the latest value. An effect that should run only
 * when some values change, but needs the current props or functions when it
 * does, reads them from here instead of listing them as dependencies (which
 * would rerun it on every render) or silencing the lint rule.
 */
export default function useLatest(value) {
  const ref = useRef(value);
  useLayoutEffect(() => {
    ref.current = value;
  });
  return ref;
}
