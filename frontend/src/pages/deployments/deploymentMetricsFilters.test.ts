import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';
import {
  METRICS_PERIOD_PRESETS,
  customWindow,
  formatPerDay,
  formatRate,
  formatSeconds,
  isBeyondMaxWindow,
  periodWindow,
  presetRange,
} from './deploymentMetricsFilters';

const NOW = new Date('2026-10-23T15:30:00Z');

describe('periodWindow', () => {
  it.each([
    ['7d', '2026-10-17T00:00:00.000Z'],
    ['30d', '2026-09-24T00:00:00.000Z'],
    ['90d', '2026-07-26T00:00:00.000Z'],
  ] as const)('%s ends at the start of tomorrow (UTC)', (preset, from) => {
    expect(periodWindow(preset, NOW, null)).toEqual({ from, to: '2026-10-24T00:00:00.000Z' });
  });

  it('a custom preset without a picked range falls back to 30 days', () => {
    expect(periodWindow('custom', NOW, null)).toEqual(periodWindow('30d', NOW, null));
  });

  it('a custom range spans the picked days inclusively as UTC days', () => {
    const range: [dayjs.Dayjs, dayjs.Dayjs] = [dayjs('2026-10-01'), dayjs('2026-10-03')];
    expect(periodWindow('custom', NOW, range)).toEqual({
      from: '2026-10-01T00:00:00.000Z',
      to: '2026-10-04T00:00:00.000Z',
    });
    expect(customWindow(dayjs('2026-12-31'), dayjs('2026-12-31'))).toEqual({
      from: '2026-12-31T00:00:00.000Z',
      to: '2027-01-01T00:00:00.000Z',
    });
  });

  it('lists the presets in display order', () => {
    expect(METRICS_PERIOD_PRESETS).toEqual(['7d', '30d', '90d', 'custom']);
  });
});

describe('presetRange', () => {
  it.each(['7d', '30d', '90d'] as const)('round-trips %s through customWindow', (preset) => {
    const [start, end] = presetRange(preset, NOW);
    expect(customWindow(start, end)).toEqual(periodWindow(preset, NOW, null));
  });
});

describe('isBeyondMaxWindow', () => {
  const from = dayjs('2026-01-01');
  it('offers every day until the first pick', () => {
    expect(isBeyondMaxWindow(dayjs('2030-01-01'), undefined)).toBe(false);
  });
  it('allows 366 inclusive days either side of the first pick', () => {
    expect(isBeyondMaxWindow(from.add(365, 'day'), from)).toBe(false);
    expect(isBeyondMaxWindow(from.subtract(365, 'day'), from)).toBe(false);
  });
  it('blocks a 367th day', () => {
    expect(isBeyondMaxWindow(from.add(366, 'day'), from)).toBe(true);
    expect(isBeyondMaxWindow(from.subtract(366, 'day'), from)).toBe(true);
  });
});

describe('formatters', () => {
  it('humanises seconds and dashes a missing percentile', () => {
    expect(formatSeconds(undefined)).toBe('—');
    expect(formatSeconds(null)).toBe('—');
    expect(formatSeconds(0)).toBe('<1m');
    expect(formatSeconds(4320)).toBe('1h 12m');
    expect(formatSeconds(273_600)).toBe('3d 4h');
  });

  it('formats rates as percentages and dashes a missing rate', () => {
    expect(formatRate(undefined, 'en')).toBe('—');
    expect(formatRate(0.5, 'en')).toBe('50%');
    expect(formatRate(0.0952, 'en')).toBe('9.5%');
    expect(formatRate(0, 'en')).toBe('0%');
  });

  it('formats a per-day frequency to two decimals', () => {
    expect(formatPerDay(1.4, 'en')).toBe('1.4');
    expect(formatPerDay(2 / 3, 'en')).toBe('0.67');
  });
});
