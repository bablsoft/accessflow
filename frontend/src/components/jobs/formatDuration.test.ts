import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import { formatDurationMs, formatIsoDuration } from './formatDuration';

const t = ((key: string, opts?: Record<string, unknown>) =>
  `${key}:${JSON.stringify(opts ?? {})}`) as unknown as TFunction;

describe('formatDurationMs', () => {
  it('renders a dash for a missing duration', () => {
    expect(formatDurationMs(t, null)).toBe('—');
    expect(formatDurationMs(t, undefined)).toBe('—');
  });

  it('picks milliseconds, seconds, then minutes', () => {
    expect(formatDurationMs(t, 250)).toBe('admin.jobs.duration_ms:{"value":250}');
    expect(formatDurationMs(t, 1500)).toBe('admin.jobs.duration_s:{"value":"1.5"}');
    expect(formatDurationMs(t, 125_000)).toBe('admin.jobs.duration_m:{"minutes":2,"seconds":5}');
  });
});

describe('formatIsoDuration', () => {
  it('renders each ISO unit through t()', () => {
    expect(formatIsoDuration(t, 'PT24H')).toBe('admin.jobs.unit_h:{"value":24}');
    expect(formatIsoDuration(t, 'P1DT2H')).toBe('admin.jobs.unit_d:{"value":1} admin.jobs.unit_h:{"value":2}');
    expect(formatIsoDuration(t, 'PT5M30S')).toBe('admin.jobs.unit_m:{"value":5} admin.jobs.unit_s:{"value":30}');
  });

  it('falls back to the raw text or a dash', () => {
    expect(formatIsoDuration(t, null)).toBe('—');
    expect(formatIsoDuration(t, '0 0 * * * *')).toBe('0 0 * * * *');
    expect(formatIsoDuration(t, 'P')).toBe('P');
  });
});
