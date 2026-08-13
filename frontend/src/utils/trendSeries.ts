/**
 * Pure shaping helpers turning the dashboard's sparse day-bucketed trend payloads
 * (`{date, key, count}` rows that only exist when count > 0) into the dense, Date-keyed
 * structures the Bklit charts consume (AF-498 redesign).
 */

export interface DaySeriesPoint {
  /** ISO date (yyyy-mm-dd) of the day bucket. */
  date: string;
  /** Series label the point belongs to (already localized). */
  label: string;
  count: number;
}

const DAY_MS = 24 * 60 * 60 * 1000;

export type TrendsRange = '7d' | '30d' | '90d';

const RANGE_DAYS: Record<TrendsRange, number> = { '7d': 7, '30d': 30, '90d': 90 };

export interface TrendsWindow {
  from: string;
  to: string;
}

/**
 * Day-granular window anchors (UTC): `to` is the start of tomorrow so today's activity is
 * always in range, and the query key stays identical across mounts within a day — a
 * ms-precision anchor would mint a fresh cache entry per mount and exclude activity newer
 * than the mount from Refresh/WS-invalidated refetches.
 */
export function trendsFiltersForRange(range: TrendsRange, now: Date): TrendsWindow {
  const to = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
  const from = new Date(to.getTime() - RANGE_DAYS[range] * DAY_MS);
  return { from: from.toISOString(), to: to.toISOString() };
}

function utcDayStart(iso: string): number {
  const d = new Date(iso);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
}

function eachDay(fromIso: string, toIso: string): number[] {
  const start = utcDayStart(fromIso);
  const end = utcDayStart(toIso);
  const days: number[] = [];
  for (let t = start; t < end; t += DAY_MS) {
    days.push(t);
  }
  return days;
}

/**
 * Dense per-day rows with one numeric key per series label and zero-fill for missing days —
 * the record shape `AreaChart`/`LineChart` expect (`{ date: Date, [label]: number }`).
 */
export function pivotDailySeries(
  points: DaySeriesPoint[],
  fromIso: string,
  toIso: string,
): Array<Record<string, unknown>> {
  const labels = [...new Set(points.map((p) => p.label))];
  const byDay = new Map<number, Map<string, number>>();
  for (const p of points) {
    const day = utcDayStart(p.date);
    const forDay = byDay.get(day) ?? new Map<string, number>();
    forDay.set(p.label, (forDay.get(p.label) ?? 0) + p.count);
    byDay.set(day, forDay);
  }
  return eachDay(fromIso, toIso).map((day) => {
    const row: Record<string, unknown> = { date: new Date(day) };
    const forDay = byDay.get(day);
    for (const label of labels) {
      row[label] = forDay?.get(label) ?? 0;
    }
    return row;
  });
}

/** Per-day totals across every series (sparkline / heatmap input), zero-filled. */
export function dailyTotals(
  points: Array<{ date: string; count: number }>,
  fromIso: string,
  toIso: string,
): Array<{ date: Date; value: number }> {
  const byDay = new Map<number, number>();
  for (const p of points) {
    const day = utcDayStart(p.date);
    byDay.set(day, (byDay.get(day) ?? 0) + p.count);
  }
  return eachDay(fromIso, toIso).map((day) => ({
    date: new Date(day),
    value: byDay.get(day) ?? 0,
  }));
}

/** Second-half vs first-half comparison of a totals window (stat-tile delta chips). */
export function halfWindowDelta(
  totals: Array<{ value: number }>,
): { current: number; previous: number; delta: number } {
  const mid = Math.floor(totals.length / 2);
  const previous = totals.slice(0, mid).reduce((s, t) => s + t.value, 0);
  const current = totals.slice(mid).reduce((s, t) => s + t.value, 0);
  return { current, previous, delta: current - previous };
}

export interface HeatmapWeekBin {
  count: number;
  bin: number;
  date: Date;
}

export interface HeatmapWeekColumn {
  bin: number;
  bins: HeatmapWeekBin[];
}

/**
 * GitHub-style weekly columns for the activity heatmap: one column per ISO-adjacent week,
 * rows 0–6 = Monday–Sunday (UTC), built from zero-filled daily totals.
 */
export function weeklyHeatmapColumns(
  totals: Array<{ date: Date; value: number }>,
): HeatmapWeekColumn[] {
  const columns: HeatmapWeekColumn[] = [];
  let week: HeatmapWeekBin[] = [];
  for (const t of totals) {
    // getUTCDay: 0 = Sunday; shift so 0 = Monday.
    const row = (t.date.getUTCDay() + 6) % 7;
    if (row === 0 && week.length > 0) {
      columns.push({ bin: columns.length, bins: week });
      week = [];
    }
    week.push({ count: t.value, bin: row, date: t.date });
  }
  if (week.length > 0) {
    columns.push({ bin: columns.length, bins: week });
  }
  return columns;
}
