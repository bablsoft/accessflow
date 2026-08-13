import { useMemo } from 'react';
import { CalendarOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { WidgetError } from '@/components/dashboard/WidgetError';
import {
  HeatmapCells,
  HeatmapChart,
  HeatmapInteractionBoundary,
  HeatmapInteractionProvider,
  HeatmapLegend,
  HeatmapTooltip,
  HeatmapXAxis,
  HeatmapYAxis,
} from '@/components/charts';
import { dashboardKeys, fetchMyQueryTrends } from '@/api/dashboard';
import { dailyTotals, trendsFiltersForRange, weeklyHeatmapColumns } from '@/utils/trendSeries';

/**
 * GitHub-style calendar heatmap of the user's query activity over the last 90 days (AF-498
 * redesign, Bklit heatmap). Always the full 90-day horizon regardless of the trends range
 * control — a shorter heatmap reads as an almost-empty grid.
 */
export function ActivityHeatmapWidget() {
  const { t } = useTranslation();
  const filters = useMemo(() => trendsFiltersForRange('90d', new Date()), []);

  const trendsQuery = useQuery({
    queryKey: dashboardKeys.trends(filters),
    queryFn: () => fetchMyQueryTrends(filters),
  });

  const { columns, hasActivity } = useMemo(() => {
    const totals = dailyTotals(
      trendsQuery.data?.status_by_day ?? [],
      filters.from ?? '',
      filters.to ?? '',
    );
    return {
      columns: weeklyHeatmapColumns(totals),
      hasActivity: totals.some((d) => d.value > 0),
    };
  }, [trendsQuery.data, filters]);

  if (trendsQuery.isError) {
    return <WidgetError error={trendsQuery.error} onRetry={() => void trendsQuery.refetch()} />;
  }
  if (!trendsQuery.isLoading && !hasActivity) {
    return (
      <EmptyState
        size="sm"
        icon={<CalendarOutlined style={{ fontSize: 16 }} />}
        title={t('dashboard.activity.empty')}
      />
    );
  }
  return (
    <HeatmapInteractionProvider>
      <HeatmapInteractionBoundary>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, width: '100%' }}>
          <HeatmapChart
            data={columns}
            layout="fluid"
            gap={3}
            status={trendsQuery.isLoading ? 'loading' : 'ready'}
            loadingLabel={t('dashboard.trends.loading')}
          >
            <HeatmapCells />
            <HeatmapXAxis />
            <HeatmapYAxis />
            <HeatmapTooltip />
          </HeatmapChart>
          <HeatmapLegend />
        </div>
      </HeatmapInteractionBoundary>
    </HeatmapInteractionProvider>
  );
}
