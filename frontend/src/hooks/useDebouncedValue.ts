import { useEffect, useState } from 'react';

/**
 * Trails `value` by `delayMs`, settling only once it has stopped changing. Not a fetch — it just
 * keys the live-lint query (#865) on a draft the author has paused on, rather than every keystroke.
 * A non-positive delay passes the value straight through.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    if (delayMs <= 0) return undefined;
    const handle = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(handle);
  }, [value, delayMs]);
  return delayMs <= 0 ? value : debounced;
}
