import { describe, expect, it } from 'vitest';
import {
  accessGrantStatusColor,
  anomalyStatusColor,
  breakGlassStatusColor,
  statusColor,
} from './statusColors';
import type { BehaviorAnomalyStatus, BreakGlassEventStatus } from '@/types/api';

describe('anomalyStatusColor', () => {
  it('returns a distinct colour triple for each anomaly status', () => {
    const statuses: BehaviorAnomalyStatus[] = ['OPEN', 'ACKNOWLEDGED', 'DISMISSED'];
    for (const s of statuses) {
      const c = anomalyStatusColor(s);
      expect(c.fg).toMatch(/^var\(--/);
      expect(c.bg).toMatch(/^var\(--/);
      expect(c.border).toMatch(/^var\(--/);
    }
  });

  it('uses the critical palette for OPEN and the neutral palette for DISMISSED', () => {
    expect(anomalyStatusColor('OPEN').fg).toBe('var(--risk-crit)');
    expect(anomalyStatusColor('ACKNOWLEDGED').fg).toBe('var(--status-warn)');
    expect(anomalyStatusColor('DISMISSED').fg).toBe('var(--fg-muted)');
  });
});

describe('breakGlassStatusColor', () => {
  it('returns a colour triple for each break-glass status', () => {
    const statuses: BreakGlassEventStatus[] = ['PENDING_REVIEW', 'REVIEWED'];
    for (const s of statuses) {
      const c = breakGlassStatusColor(s);
      expect(c.fg).toMatch(/^var\(--/);
      expect(c.bg).toMatch(/^var\(--/);
      expect(c.border).toMatch(/^var\(--/);
    }
  });

  it('uses the critical palette for PENDING_REVIEW and the low palette for REVIEWED', () => {
    expect(breakGlassStatusColor('PENDING_REVIEW').fg).toBe('var(--risk-crit)');
    expect(breakGlassStatusColor('REVIEWED').fg).toBe('var(--risk-low)');
  });
});

describe('statusColor / accessGrantStatusColor smoke', () => {
  it('still resolves existing status palettes', () => {
    expect(statusColor('APPROVED').fg).toBe('var(--risk-low)');
    expect(accessGrantStatusColor('PENDING').fg).toBe('var(--status-info)');
  });
});
