import { describe, expect, it } from 'vitest';
import { riskColor } from '../riskColors';

describe('riskColor', () => {
  it('maps LOW to green tokens', () => {
    expect(riskColor('LOW')).toEqual({
      fg: 'var(--risk-low)',
      bg: 'var(--risk-low-bg)',
      border: 'var(--risk-low-border)',
    });
  });

  it('maps MEDIUM to amber tokens', () => {
    expect(riskColor('MEDIUM').fg).toBe('var(--risk-med)');
  });

  it('maps HIGH to orange tokens', () => {
    expect(riskColor('HIGH').fg).toBe('var(--risk-high)');
  });

  it('maps CRITICAL to red tokens', () => {
    expect(riskColor('CRITICAL').fg).toBe('var(--risk-crit)');
  });

  it('returns a triple with fg, bg, border for every level', () => {
    const levels = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
    for (const level of levels) {
      const c = riskColor(level);
      expect(c.fg).toMatch(/var\(--/);
      expect(c.bg).toMatch(/var\(--/);
      expect(c.border).toMatch(/var\(--/);
    }
  });
});
