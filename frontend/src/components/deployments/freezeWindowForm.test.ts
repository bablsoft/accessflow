import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import type { DeploymentFreezeWindow } from '@/types/api';
import type { FreezeWindowFormValues } from './freezeWindowForm';
import {
  freezeWindowSummary,
  normalizeTime,
  timezoneOptions,
  toWireInput,
  windowMode,
} from './freezeWindowForm';

// Echoes the key plus its interpolation, so tests assert the shape without pinning English copy.
const t = ((key: string, options?: Record<string, unknown>) =>
  options
    ? `${key}:${Object.entries(options)
        .map(([k, v]) => `${k}=${String(v)}`)
        .join(',')}`
    : key) as unknown as TFunction;

const BASE_VALUES: FreezeWindowFormValues = {
  mode: 'one_off',
  pipeline_id: null,
  environment_id: null,
  starts_at: null,
  ends_at: null,
  days_of_week: [],
  start_time: null,
  end_time: null,
  timezone: null,
  behavior: 'HOLD',
  reason: null,
  enabled: true,
};

function makeWindow(overrides: Partial<DeploymentFreezeWindow>): DeploymentFreezeWindow {
  return {
    id: 'fw-1',
    pipeline_id: null,
    environment_id: null,
    starts_at: null,
    ends_at: null,
    days_of_week: [],
    start_time: null,
    end_time: null,
    timezone: null,
    behavior: 'HOLD',
    reason: null,
    enabled: true,
    created_at: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

describe('freezeWindowForm', () => {
  it('classifies mode by days_of_week presence', () => {
    expect(windowMode(makeWindow({ days_of_week: [6, 7] }))).toBe('recurring');
    expect(windowMode(makeWindow({ starts_at: '2026-09-01T00:00:00Z' }))).toBe('one_off');
  });

  it('normalizes wire LocalTime renderings to HH:mm', () => {
    expect(normalizeTime('09:30:00')).toBe('09:30');
    expect(normalizeTime('09:30')).toBe('09:30');
    expect(normalizeTime(null)).toBeNull();
  });

  it('nulls the one-off half for a recurring window', () => {
    const wire = toWireInput({
      ...BASE_VALUES,
      mode: 'recurring',
      days_of_week: [6, 7],
      start_time: '00:00',
      end_time: '23:59',
      timezone: 'Europe/Berlin',
      behavior: 'REJECT',
      reason: 'weekend',
      starts_at: '2026-09-01T00:00:00Z',
      ends_at: '2026-09-02T00:00:00Z',
    });
    expect(wire.days_of_week).toEqual([6, 7]);
    expect(wire.timezone).toBe('Europe/Berlin');
    // One-off half nulled out by mode exclusivity.
    expect(wire.starts_at).toBeNull();
    expect(wire.ends_at).toBeNull();
    expect(wire.behavior).toBe('REJECT');
  });

  it('nulls the recurring half for a one-off window', () => {
    const wire = toWireInput({
      ...BASE_VALUES,
      mode: 'one_off',
      starts_at: '2026-09-01T00:00:00Z',
      ends_at: '2026-09-02T00:00:00Z',
      days_of_week: [1],
      start_time: '09:00',
      end_time: '17:00',
      timezone: 'UTC',
    });
    expect(wire.starts_at).toBe('2026-09-01T00:00:00Z');
    expect(wire.days_of_week).toBeNull();
    expect(wire.start_time).toBeNull();
    expect(wire.end_time).toBeNull();
    expect(wire.timezone).toBeNull();
  });

  it('drops environment scoping without a pipeline (backend rejects env-only scope)', () => {
    const wire = toWireInput({
      ...BASE_VALUES,
      pipeline_id: null,
      environment_id: 'env-1',
      starts_at: '2026-09-01T00:00:00Z',
      ends_at: '2026-09-02T00:00:00Z',
    });
    expect(wire.environment_id).toBeNull();

    const scoped = toWireInput({
      ...BASE_VALUES,
      pipeline_id: 'p-1',
      environment_id: 'env-1',
      starts_at: '2026-09-01T00:00:00Z',
      ends_at: '2026-09-02T00:00:00Z',
    });
    expect(scoped.environment_id).toBe('env-1');
  });

  it('summarizes a one-off window with its date range', () => {
    const summary = freezeWindowSummary(
      t,
      makeWindow({ starts_at: '2026-09-01T00:00:00Z', ends_at: '2026-09-02T00:00:00Z' }),
    );
    expect(summary).toContain('deploygov.freezeWindows.summary_one_off');
  });

  it('summarizes a recurring window with sorted weekday labels', () => {
    const summary = freezeWindowSummary(
      t,
      makeWindow({ days_of_week: [7, 6], start_time: '00:00', end_time: '23:59', timezone: 'UTC' }),
    );
    expect(summary).toContain('deploygov.freezeWindows.summary_recurring');
    // Sorted 6 (Saturday) before 7 (Sunday).
    expect(summary.indexOf('SATURDAY')).toBeLessThan(summary.indexOf('SUNDAY'));
    expect(summary).toContain('timezone=UTC');
  });

  it('lists IANA timezones with the local zone first', () => {
    const zones = timezoneOptions();
    expect(zones.length).toBeGreaterThan(0);
    expect(zones[0]).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect(new Set(zones).size).toBe(zones.length);
  });
});
