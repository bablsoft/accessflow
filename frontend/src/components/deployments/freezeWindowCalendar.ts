import type { DeploymentFreezeWindow, FreezeBehavior } from '@/types/api';

/**
 * One shaded block on the week strip: an ISO day (1–7) and a minute-of-day range.
 * A recurring window that wraps midnight (start > end) is split into two segments —
 * the tail of the start day and the head of the following day.
 */
export interface WeekSegment {
  windowId: string;
  day: number;
  startMinutes: number;
  endMinutes: number;
  behavior: FreezeBehavior;
}

export function timeStringToMinutes(time: string | null): number | null {
  if (!time) return null;
  const [hh, mm] = time.split(':');
  const hours = Number(hh);
  const minutes = Number(mm ?? '0');
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return null;
  return hours * 60 + minutes;
}

const nextDay = (day: number): number => (day % 7) + 1;

/** Converts the enabled recurring windows into per-day shaded segments for the week strip. */
export function buildWeekSegments(windows: DeploymentFreezeWindow[]): WeekSegment[] {
  const segments: WeekSegment[] = [];
  for (const window of windows) {
    const daysOfWeek = window.days_of_week ?? [];
    if (!window.enabled || daysOfWeek.length === 0) continue;
    const start = timeStringToMinutes(window.start_time);
    const end = timeStringToMinutes(window.end_time);
    if (start == null || end == null) continue;
    for (const day of daysOfWeek) {
      if (day < 1 || day > 7) continue;
      if (start < end) {
        segments.push({
          windowId: window.id,
          day,
          startMinutes: start,
          endMinutes: end,
          behavior: window.behavior,
        });
      } else {
        // Wraps midnight: shade to the end of this day and from the start of the next.
        segments.push({
          windowId: window.id,
          day,
          startMinutes: start,
          endMinutes: 24 * 60,
          behavior: window.behavior,
        });
        segments.push({
          windowId: window.id,
          day: nextDay(day),
          startMinutes: 0,
          endMinutes: end,
          behavior: window.behavior,
        });
      }
    }
  }
  return segments;
}
