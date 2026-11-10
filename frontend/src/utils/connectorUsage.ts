import type { ApiConnectorUsageDay } from '@/types/api';

export type ConnectorUsageRange = 'month' | '7d' | '30d' | '90d';

export const CONNECTOR_USAGE_RANGES: readonly ConnectorUsageRange[] = ['month', '7d', '30d', '90d'];

const DAY_MS = 86_400_000;
const RANGE_DAYS: Record<Exclude<ConnectorUsageRange, 'month'>, number> = {
  '7d': 7,
  '30d': 30,
  '90d': 90,
};

export interface ConnectorUsageWindow {
  /** Inclusive UTC dates, YYYY-MM-DD — the usage endpoint's LocalDate params. */
  from: string;
  to: string;
}

const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/** The inclusive UTC window a range covers, ending today (≤ 92 days, the backend's cap). */
export function connectorUsageWindow(range: ConnectorUsageRange, now: Date): ConnectorUsageWindow {
  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  if (range === 'month') {
    return { from: isoDay(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)), to: isoDay(today) };
  }
  return { from: isoDay(today - (RANGE_DAYS[range] - 1) * DAY_MS), to: isoDay(today) };
}

export interface UsageSeriesLabels {
  calls: string;
  failed: string;
  throttled: string;
}

/** Dense per-day chart rows (`{ date: Date, [label]: number }`), zero-filled across the window. */
export function connectorUsageRows(
  days: ApiConnectorUsageDay[],
  window: ConnectorUsageWindow,
  labels: UsageSeriesLabels,
): Array<Record<string, unknown>> {
  const byDay = new Map(days.map((d) => [d.date, d]));
  const rows: Array<Record<string, unknown>> = [];
  const end = Date.parse(`${window.to}T00:00:00Z`);
  for (let ms = Date.parse(`${window.from}T00:00:00Z`); ms <= end; ms += DAY_MS) {
    const day = byDay.get(isoDay(ms));
    rows.push({
      date: new Date(ms),
      [labels.calls]: day?.calls ?? 0,
      [labels.failed]: day?.failed_calls ?? 0,
      [labels.throttled]: day?.throttled_calls ?? 0,
    });
  }
  return rows;
}
