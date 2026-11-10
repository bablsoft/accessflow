import { describe, expect, it } from 'vitest';
import { CONNECTOR_USAGE_RANGES, connectorUsageRows, connectorUsageWindow } from './connectorUsage';

const now = new Date('2026-11-10T22:30:00Z');

describe('connectorUsageWindow', () => {
  it('lists the four ranges', () => {
    expect(CONNECTOR_USAGE_RANGES).toEqual(['month', '7d', '30d', '90d']);
  });

  it('covers the current UTC month up to today', () => {
    expect(connectorUsageWindow('month', now)).toEqual({ from: '2026-11-01', to: '2026-11-10' });
  });

  it('covers N inclusive days ending today', () => {
    expect(connectorUsageWindow('7d', now)).toEqual({ from: '2026-11-04', to: '2026-11-10' });
    expect(connectorUsageWindow('30d', now)).toEqual({ from: '2026-10-12', to: '2026-11-10' });
    expect(connectorUsageWindow('90d', now).from).toBe('2026-08-13');
  });
});

describe('connectorUsageRows', () => {
  const labels = { calls: 'Calls', failed: 'Failed', throttled: 'Throttled' };

  it('zero-fills every day of the window and maps the three series', () => {
    const rows = connectorUsageRows(
      [{ date: '2026-11-09', calls: 4, failed_calls: 1, throttled_calls: 2, spend_micros: 0 }],
      { from: '2026-11-08', to: '2026-11-10' },
      labels,
    );
    expect(rows).toHaveLength(3);
    expect(rows[0]).toEqual({ date: new Date('2026-11-08T00:00:00Z'), Calls: 0, Failed: 0, Throttled: 0 });
    expect(rows[1]).toEqual({ date: new Date('2026-11-09T00:00:00Z'), Calls: 4, Failed: 1, Throttled: 2 });
  });
});
