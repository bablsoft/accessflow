import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * Keeps a page's active tab in `?tab=`, so a reload, a shared link and the docs all land on the
 * same tab. An absent or unknown value falls back to `fallback`; the fallback is not written to
 * the URL. Other query parameters are preserved.
 */
export function useUrlTab<K extends string>(
  keys: readonly K[],
  fallback: K,
): [K, (next: string) => void] {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get('tab');
  const active = keys.find((key) => key === requested) ?? fallback;
  const setTab = useCallback(
    (next: string) => {
      setSearchParams(
        (prev) => {
          const params = new URLSearchParams(prev);
          if (next === fallback) params.delete('tab');
          else params.set('tab', next);
          return params;
        },
        { replace: true },
      );
    },
    [fallback, setSearchParams],
  );
  return [active, setTab];
}
