import { useMemo, useState } from 'react';
import { Segmented } from 'antd';
import { LineChartOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { WidgetError } from '@/components/dashboard/WidgetError';
import { Area, AreaChart, ChartTooltip, Grid, XAxis } from '@/components/charts';
import {
  dashboardKeys,
  fetchMyApiRequestTrends,
  fetchMyQueryTrends,
} from '@/api/dashboard';
import {
  usePreferencesStore,
  type DashboardTrendsRange,
} from '@/store/preferencesStore';
import { fmtDateOnly } from '@/utils/dateFormat';
import { queryStatusLabel, riskLevelLabel } from '@/utils/enumLabels';
import { riskColor } from '@/utils/riskColors';
import { statusColor } from '@/utils/statusColors';
import { pivotDailySeries, trendsFiltersForRange, type DaySeriesPoint } from '@/utils/trendSeries';

type TrendMetric = 'status' | 'risk';

interface TrendsWidgetProps {
  /** Which trend series this instance renders: the user's queries or governed API requests. */
  kind: 'queries' | 'apiRequests';
}

/**
 * Status/risk trend chart over the user's own queries or API requests (AF-498 redesign): a
 * Bklit gradient area chart with a metric toggle and a shared 7d/30d/90d range control
 * (persisted as a UI preference). Series colors come from the status/risk tokens, so the
 * chart follows the app theme via the CSS-variable bridge in styles/bklit.css.
 */
export function TrendsWidget({ kind }: TrendsWidgetProps) {
  const { t } = useTranslation();
  const [metric, setMetric] = useState<TrendMetric>('status');
  const range = usePreferencesStore((s) => s.dashboardTrendsRange);
  const setRange = usePreferencesStore((s) => s.setDashboardTrendsRange);

  const filters = useMemo(() => trendsFiltersForRange(range, new Date()), [range]);

  const trendsQuery = useQuery({
    queryKey:
      kind === 'queries' ? dashboardKeys.trends(filters) : dashboardKeys.apiRequestTrends(filters),
    queryFn: () =>
      kind === 'queries' ? fetchMyQueryTrends(filters) : fetchMyApiRequestTrends(filters),
  });

  const emptyKey = kind === 'queries' ? 'dashboard.trends.empty' : 'dashboard.api_request_trends.empty';

  const data = trendsQuery.data;
  const { points, colors } = useMemo(() => {
    const colorByLabel = new Map<string, string>();
    if (!data) return { points: [] as DaySeriesPoint[], colors: colorByLabel };
    const mapped: DaySeriesPoint[] =
      metric === 'status'
        ? data.status_by_day.map((b) => {
            const label = queryStatusLabel(t, b.status);
            colorByLabel.set(label, statusColor(b.status).fg);
            return { date: b.date, label, count: b.count };
          })
        : data.risk_by_day.map((b) => {
            const label = riskLevelLabel(t, b.risk_level);
            colorByLabel.set(label, riskColor(b.risk_level).fg);
            return { date: b.date, label, count: b.count };
          });
    return { points: mapped, colors: colorByLabel };
  }, [data, metric, t]);

  const rows = useMemo(
    () => pivotDailySeries(points, filters.from ?? '', filters.to ?? ''),
    [points, filters],
  );
  const labels = [...colors.keys()];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <Segmented<TrendMetric>
          size="small"
          value={metric}
          onChange={setMetric}
          options={[
            { value: 'status', label: t('dashboard.trends.metric_status') },
            { value: 'risk', label: t('dashboard.trends.metric_risk') },
          ]}
        />
        <Segmented<DashboardTrendsRange>
          size="small"
          value={range}
          onChange={setRange}
          options={[
            { value: '7d', label: t('dashboard.trends.range_7d') },
            { value: '30d', label: t('dashboard.trends.range_30d') },
            { value: '90d', label: t('dashboard.trends.range_90d') },
          ]}
        />
        <span className="muted" style={{ fontSize: 11, marginLeft: 'auto' }}>
          {t('dashboard.trends.window', {
            from: fmtDateOnly(filters.from ?? '', { utc: true }),
            // `to` is the exclusive start of tomorrow; display the inclusive last day instead.
            to: fmtDateOnly(new Date(new Date(filters.to ?? '').getTime() - 1), { utc: true }),
          })}
        </span>
      </div>
      {trendsQuery.isError ? (
        <WidgetError error={trendsQuery.error} onRetry={() => void trendsQuery.refetch()} />
      ) : !trendsQuery.isLoading && labels.length === 0 ? (
        <EmptyState
          size="sm"
          icon={<LineChartOutlined style={{ fontSize: 16 }} />}
          title={t(emptyKey)}
        />
      ) : (
        <>
          <AreaChart
            data={rows}
            aspectRatio="2.6 / 1"
            status={trendsQuery.isLoading ? 'loading' : 'ready'}
            loadingLabel={t('dashboard.trends.loading')}
          >
            <Grid horizontal />
            {labels.map((label) => (
              <Area
                key={label}
                dataKey={label}
                fill={colors.get(label)}
                stroke={colors.get(label)}
                strokeWidth={2}
                fillOpacity={0.25}
              />
            ))}
            <XAxis />
            <ChartTooltip />
          </AreaChart>
          {labels.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 14px' }}>
              {labels.map((label) => (
                <span
                  key={label}
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}
                  className="muted"
                >
                  <span
                    aria-hidden
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: '50%',
                      background: colors.get(label),
                      display: 'inline-block',
                    }}
                  />
                  {label}
                </span>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
