import { useEffect, type RefObject } from 'react';
import { DURATION_MS } from '@/theme/palette';

const REDUCED = '(prefers-reduced-motion: reduce)';

/**
 * Plays a short fade-and-rise on `ref` each time `key` changes, without remounting its children
 * (so a page keeps its state when only a route parameter changes).
 */
export function useEnterAnimation(ref: RefObject<HTMLElement | null>, key: string): void {
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof el.animate !== 'function' || window.matchMedia(REDUCED).matches) return;
    const animation = el.animate(
      [
        { opacity: 0, transform: 'translateY(4px)' },
        { opacity: 1, transform: 'none' },
      ],
      { duration: DURATION_MS.base, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' },
    );
    return () => animation.cancel();
  }, [ref, key]);
}
