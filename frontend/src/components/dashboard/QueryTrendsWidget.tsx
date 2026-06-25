import { Skeleton } from 'antd';
import { Line } from '@ant-design/charts';
import { LineChartOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { dashboardKeys, fetchMyQueryTrends } from '@/api/dashboard';
import { queryStatusLabel, riskLevelLabel } from '@/utils/enumLabels';
import type { MyQueryTrends } from '@/types/api';

/** Status- and risk-level trend sparklines over the current user's own queries (AF-498). */
export function QueryTrendsWidget() {
  const { t } = useTranslation();
  const trendsQuery = useQuery({
    queryKey: dashboardKeys.trends({}),
    queryFn: () => fetchMyQueryTrends({}),
  });

  if (trendsQuery.isLoading || !trendsQuery.data) {
    return <Skeleton active paragraph={{ rows: 4 }} />;
  }
  const data: MyQueryTrends = trendsQuery.data;
  if (data.status_by_day.length === 0 && data.risk_by_day.length === 0) {
    return (
      <EmptyState
        icon={<LineChartOutlined style={{ fontSize: 20 }} />}
        title={t('dashboard.trends.empty')}
      />
    );
  }

  const statusSeries = data.status_by_day.map((b) => ({
    date: b.date,
    series: queryStatusLabel(t, b.status),
    value: b.count,
  }));
  const riskSeries = data.risk_by_day.map((b) => ({
    date: b.date,
    series: riskLevelLabel(t, b.risk_level),
    value: b.count,
  }));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <div className="mono muted" style={{ fontSize: 11, marginBottom: 6 }}>
          {t('dashboard.trends.status_title')}
        </div>
        {statusSeries.length === 0 ? (
          <EmptyState title={t('dashboard.trends.empty')} />
        ) : (
          <Line
            data={statusSeries}
            xField="date"
            yField="value"
            colorField="series"
            height={220}
            legend={{ color: { position: 'top' } }}
            axis={{
              x: { title: t('dashboard.trends.axis_date') },
              y: { title: t('dashboard.trends.axis_count') },
            }}
          />
        )}
      </div>
      <div>
        <div className="mono muted" style={{ fontSize: 11, marginBottom: 6 }}>
          {t('dashboard.trends.risk_title')}
        </div>
        {riskSeries.length === 0 ? (
          <EmptyState title={t('dashboard.trends.empty')} />
        ) : (
          <Line
            data={riskSeries}
            xField="date"
            yField="value"
            colorField="series"
            height={220}
            legend={{ color: { position: 'top' } }}
            axis={{
              x: { title: t('dashboard.trends.axis_date') },
              y: { title: t('dashboard.trends.axis_count') },
            }}
          />
        )}
      </div>
    </div>
  );
}
