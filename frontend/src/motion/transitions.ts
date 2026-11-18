import type { Transition, Variants } from 'motion/react';
import { DURATION_MS, EASE_IN, EASE_OUT } from '../theme/palette';

const s = (ms: number) => ms / 1000;

/** Tween presets mirroring the `--dur-*` / `--ease-*` CSS tokens. */
export const transitions = {
  fast: { duration: s(DURATION_MS.fast), ease: EASE_OUT },
  enter: { duration: s(DURATION_MS.base), ease: EASE_OUT },
  exit: { duration: s(DURATION_MS.fast), ease: EASE_IN },
  overlay: { duration: s(DURATION_MS.slow), ease: EASE_OUT },
  /** Layout and shared-element moves: no overshoot, keeps velocity when interrupted. */
  layout: { type: 'spring', visualDuration: s(DURATION_MS.slow), bounce: 0 },
} as const satisfies Record<string, Transition>;

/** Fade with a short rise — page content, panels, list items. */
export const fadeRise: Variants = {
  hidden: { opacity: 0, y: 4 },
  visible: { opacity: 1, y: 0, transition: transitions.enter },
  exit: { opacity: 0, transition: transitions.exit },
};

export const STAGGER_STEP_S = 0.035;
export const STAGGER_MAX_ITEMS = 8;

/** Entrance delay for the item at `index`; items past the cap enter together. */
export function staggerDelay(index: number): number {
  return Math.min(Math.max(index, 0), STAGGER_MAX_ITEMS) * STAGGER_STEP_S;
}
