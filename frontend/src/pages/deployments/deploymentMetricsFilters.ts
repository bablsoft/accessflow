import dayjs, { type Dayjs } from 'dayjs';
import { formatDurationCompact } from '@/utils/accessTtl';
import { trendsFiltersForRange, type TrendsWindow } from '@/utils/trendSeries';

/**
 * Pure helpers for the deployment-metrics page (#987): period presets to `[from, to)` instants
 * and the formatters for the server's seconds / decimal rates. The page never derives a metric —
 * these only turn the #984 response into display strings.
 */

export type MetricsPeriodPreset = '7d' | '30d' | '90d' | 'custom';

export const METRICS_PERIOD_PRESETS: readonly MetricsPeriodPreset[] = ['7d', '30d', '90d', 'custom'];

/** The backend's window cap (`DEPLOYMENT_METRICS_PERIOD_INVALID` beyond it). */
export const MAX_METRICS_WINDOW_DAYS = 366;

const PLACEHOLDER = '—';

/**
 * The calendar days picked in a range picker as UTC days: `from` is the start of the first day,
 * `to` the start of the day after the last, so the window lines up with the server's UTC buckets.
 */
export function customWindow(start: Dayjs, end: Dayjs): TrendsWindow {
  const from = new Date(Date.UTC(start.year(), start.month(), start.date()));
  const to = new Date(Date.UTC(end.year(), end.month(), end.date() + 1));
  return { from: from.toISOString(), to: to.toISOString() };
}

/** `[from, to)` for a preset (day-aligned, so the query key is stable within a day). */
export function periodWindow(
  preset: MetricsPeriodPreset,
  now: Date,
  custom: [Dayjs, Dayjs] | null,
): TrendsWindow {
  if (preset === 'custom') {
    return custom ? customWindow(custom[0], custom[1]) : trendsFiltersForRange('30d', now);
  }
  return trendsFiltersForRange(preset, now);
}

/**
 * The calendar days of a preset as picker values, so switching to "Custom range" starts on the
 * exact window the preset showed: the preset's UTC days re-read as local calendar days, which is
 * what {@link customWindow} turns back into the same UTC instants.
 */
export function presetRange(preset: Exclude<MetricsPeriodPreset, 'custom'>, now: Date): [Dayjs, Dayjs] {
  const window = trendsFiltersForRange(preset, now);
  const asLocalDay = (iso: string, offsetDays: number) => {
    const d = new Date(iso);
    return dayjs(new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + offsetDays));
  };
  return [asLocalDay(window.from, 0), asLocalDay(window.to, -1)];
}

/** Range-picker guard: a span of more than {@link MAX_METRICS_WINDOW_DAYS} days is not offered. */
export function isBeyondMaxWindow(current: Dayjs, from: Dayjs | undefined): boolean {
  if (!from) return false;
  return Math.abs(current.startOf('day').diff(from.startOf('day'), 'day')) >= MAX_METRICS_WINDOW_DAYS;
}

/** Whole seconds → "1h 12m" / "3d 4h"; a missing percentile (empty sample) → "—". */
export function formatSeconds(seconds: number | null | undefined): string {
  if (seconds == null) return PLACEHOLDER;
  return formatDurationCompact(seconds * 1000);
}

/** A decimal rate → "9.5%"; a missing rate (zero denominator) → "—". */
export function formatRate(rate: number | null | undefined, locale: string): string {
  if (rate == null) return PLACEHOLDER;
  return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 }).format(rate);
}

export function formatPerDay(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value);
}
