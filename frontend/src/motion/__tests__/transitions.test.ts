import { describe, expect, it } from 'vitest';
import { STAGGER_MAX_ITEMS, STAGGER_STEP_S, staggerDelay, transitions } from '../transitions';

describe('transitions', () => {
  it('makes exits shorter than enters', () => {
    expect(transitions.exit.duration).toBeLessThan(transitions.enter.duration);
    expect(transitions.exit.duration / transitions.enter.duration).toBeCloseTo(0.67, 1);
  });

  it('uses a spring without overshoot for layout moves', () => {
    expect(transitions.layout.type).toBe('spring');
    expect(transitions.layout.bounce).toBe(0);
  });
});

describe('staggerDelay', () => {
  it('grows by one step per item', () => {
    expect(staggerDelay(0)).toBe(0);
    expect(staggerDelay(3)).toBeCloseTo(3 * STAGGER_STEP_S);
  });

  it('caps the delay and clamps negatives', () => {
    expect(staggerDelay(100)).toBeCloseTo(STAGGER_MAX_ITEMS * STAGGER_STEP_S);
    expect(staggerDelay(-2)).toBe(0);
  });
});
