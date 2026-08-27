import { describe, expect, it } from 'vitest';
import type { DeploymentFreezeWindow } from '@/types/api';
import { buildWeekSegments, timeStringToMinutes } from './freezeWindowCalendar';

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
    timezone: 'UTC',
    behavior: 'HOLD',
    reason: null,
    enabled: true,
    created_at: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

describe('freezeWindowCalendar', () => {
  it('parses HH:mm and HH:mm:ss into minutes', () => {
    expect(timeStringToMinutes('09:30')).toBe(570);
    expect(timeStringToMinutes('09:30:00')).toBe(570);
    expect(timeStringToMinutes('00:00')).toBe(0);
    expect(timeStringToMinutes(null)).toBeNull();
    expect(timeStringToMinutes('bogus')).toBeNull();
  });

  it('produces one segment per selected day', () => {
    const segments = buildWeekSegments([
      makeWindow({ days_of_week: [6, 7], start_time: '08:00', end_time: '18:00' }),
    ]);
    expect(segments).toEqual([
      { windowId: 'fw-1', day: 6, startMinutes: 480, endMinutes: 1080, behavior: 'HOLD' },
      { windowId: 'fw-1', day: 7, startMinutes: 480, endMinutes: 1080, behavior: 'HOLD' },
    ]);
  });

  it('splits a midnight-wrapping window across two days', () => {
    const segments = buildWeekSegments([
      makeWindow({ days_of_week: [5], start_time: '22:00', end_time: '06:00', behavior: 'REJECT' }),
    ]);
    expect(segments).toEqual([
      { windowId: 'fw-1', day: 5, startMinutes: 1320, endMinutes: 1440, behavior: 'REJECT' },
      { windowId: 'fw-1', day: 6, startMinutes: 0, endMinutes: 360, behavior: 'REJECT' },
    ]);
  });

  it('wraps Sunday into Monday', () => {
    const segments = buildWeekSegments([
      makeWindow({ days_of_week: [7], start_time: '23:00', end_time: '01:00' }),
    ]);
    expect(segments[1]?.day).toBe(1);
  });

  it('skips disabled, one-off, out-of-range and unparsable windows', () => {
    const segments = buildWeekSegments([
      makeWindow({ enabled: false, days_of_week: [1], start_time: '08:00', end_time: '10:00' }),
      makeWindow({ id: 'fw-2', starts_at: '2026-09-01T00:00:00Z', ends_at: '2026-09-02T00:00:00Z' }),
      makeWindow({ id: 'fw-3', days_of_week: [0, 8], start_time: '08:00', end_time: '10:00' }),
      makeWindow({ id: 'fw-4', days_of_week: [1], start_time: null, end_time: '10:00' }),
    ]);
    expect(segments).toEqual([]);
  });
});
