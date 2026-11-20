import { useCallback, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * Keeps a wizard's step in `?step=`, so the browser's Back and Forward buttons walk the steps.
 * A step the wizard cannot show yet (a reload drops the wizard's state, or a hand-typed URL) falls
 * back to `fallback`, and the URL is corrected to match.
 */
export function useWizardStep<K extends string>(
  keys: readonly K[],
  fallback: K,
  canShow: (key: K) => boolean,
): [K, (next: K) => void] {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get('step');
  const known = keys.find((key) => key === requested);
  const step = known && canShow(known) ? known : fallback;
  const first = keys[0];

  useEffect(() => {
    const expected = step === first ? null : step;
    if (requested === expected) return;
    setSearchParams(
      (prev) => {
        const params = new URLSearchParams(prev);
        if (expected === null) params.delete('step');
        else params.set('step', expected);
        return params;
      },
      { replace: true },
    );
  }, [requested, step, first, setSearchParams]);

  const go = useCallback(
    (next: K) => {
      setSearchParams((prev) => {
        const params = new URLSearchParams(prev);
        if (next === first) params.delete('step');
        else params.set('step', next);
        return params;
      });
    },
    [first, setSearchParams],
  );
  return [step, go];
}
