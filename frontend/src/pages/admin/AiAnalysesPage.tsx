import { useMemo, useState } from 'react';
import { Button, Card, DatePicker, Select, Skeleton, Space } from 'antd';
import { Bar, Line } from '@ant-design/charts';
import dayjs, { type Dayjs } from 'dayjs';
import { BarChartOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { aiAnalysesKeys, fetchAiAnalysisStats } from '@/api/admin';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import type {
  AiAnalysisIssueCategory,
  AiAnalysisRiskScorePoint,
  AiAnalysisStatsFilters,
  AiAnalysisTopSubmitter,
} from '@/types/api';

const DEFAULT_WINDOW_DAYS = 30;

export default function AiAnalysesPage() {
  const { t } = useTranslation();
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => [
    dayjs().subtract(DEFAULT_WINDOW_DAYS, 'day'),
    dayjs(),
  ]);
  const [datasourceId, setDatasourceId] = useState<string>('all');

  const filters: AiAnalysisStatsFilters = useMemo(
    () => ({
      from: range[0].toISOString(),
      to: range[1].toISOString(),
      datasource_id: datasourceId === 'all' ? undefined : datasourceId,
    }),
    [range, datasourceId],
  );

  const statsQuery = useQuery({
    queryKey: aiAnalysesKeys.stats(filters),
    queryFn: () => fetchAiAnalysisStats(filters),
  });

  // Reuse the existing datasource list endpoint (admin role only — same page guard).
  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ page: 0, size: 100 }),
    queryFn: () => listDatasources({ page: 0, size: 100 }),
  });

  const stats = statsQuery.data;
  const hasNoData =
    !statsQuery.isLoading &&
    !!stats &&
    stats.risk_score_over_time.length === 0 &&
    stats.top_issue_categories.length === 0 &&
    stats.top_submitters.length === 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.ai_analyses.title')}
        subtitle={t('admin.ai_analyses.subtitle')}
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => statsQuery.refetch()}>
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />
      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 8,
          alignItems: 'center',
        }}
      >
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('admin.ai_analyses.filter_range_label')}
        </span>
        <div data-testid="ai-analyses-range">
          <DatePicker.RangePicker
            showTime
            value={range}
            allowClear={false}
            onChange={(v) => {
              if (v && v[0] && v[1]) {
                setRange([v[0], v[1]]);
              }
            }}
          />
        </div>
        <span className="mono muted" style={{ fontSize: 11, marginLeft: 12 }}>
          {t('admin.ai_analyses.filter_datasource_label')}
        </span>
        <div data-testid="ai-analyses-datasource">
          <Select
            value={datasourceId}
            onChange={setDatasourceId}
            style={{ width: 260 }}
            showSearch
            optionFilterProp="label"
            loading={datasourcesQuery.isLoading}
            options={[
              { value: 'all', label: t('admin.ai_analyses.filter_datasource_all') },
              ...(datasourcesQuery.data?.content ?? []).map((d) => ({
                value: d.id,
                label: d.name,
              })),
            ]}
          />
        </div>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 28px' }}>
        {statsQuery.isLoading || !stats ? (
          <Space orientation="vertical" size={20} style={{ width: '100%' }}>
            <Skeleton active paragraph={{ rows: 6 }} />
            <Skeleton active paragraph={{ rows: 4 }} />
            <Skeleton active paragraph={{ rows: 4 }} />
          </Space>
        ) : hasNoData ? (
          <EmptyState
            icon={<BarChartOutlined style={{ fontSize: 20 }} />}
            title={t('admin.ai_analyses.empty_title')}
            description={t('admin.ai_analyses.empty_description')}
          />
        ) : (
          <Space orientation="vertical" size={20} style={{ width: '100%' }}>
            <Card
              title={t('admin.ai_analyses.chart_risk_score_title')}
              data-testid="ai-analyses-risk-chart"
            >
              <RiskScoreChart points={stats.risk_score_over_time} />
            </Card>
            <Card
              title={t('admin.ai_analyses.chart_categories_title')}
              data-testid="ai-analyses-categories-chart"
            >
              <IssueCategoriesChart rows={stats.top_issue_categories} />
            </Card>
            <Card
              title={t('admin.ai_analyses.chart_submitters_title')}
              data-testid="ai-analyses-submitters-chart"
            >
              <TopSubmittersChart rows={stats.top_submitters} />
            </Card>
          </Space>
        )}
      </div>
    </div>
  );
}

function RiskScoreChart({ points }: { points: AiAnalysisRiskScorePoint[] }) {
  const { t } = useTranslation();
  if (points.length === 0) {
    return (
      <EmptyState
        icon={<BarChartOutlined style={{ fontSize: 20 }} />}
        title={t('admin.ai_analyses.empty_title')}
      />
    );
  }
  // Build two series so the chart renders a single Line with seriesField.
  const data = points.flatMap((p) => [
    {
      date: p.date,
      series: t('admin.ai_analyses.series_success_avg_risk'),
      value: p.success_avg_risk_score ?? 0,
    },
    {
      date: p.date,
      series: t('admin.ai_analyses.series_total_count'),
      value: p.total_count,
    },
  ]);
  return (
    <Line
      data={data}
      xField="date"
      yField="value"
      colorField="series"
      height={280}
      point={{ shapeField: 'circle', sizeField: 4 }}
      legend={{ color: { position: 'top' } }}
      axis={{
        x: { title: t('admin.ai_analyses.axis_date') },
        y: { title: t('admin.ai_analyses.axis_value') },
      }}
    />
  );
}

function IssueCategoriesChart({ rows }: { rows: AiAnalysisIssueCategory[] }) {
  const { t } = useTranslation();
  if (rows.length === 0) {
    return (
      <EmptyState
        icon={<BarChartOutlined style={{ fontSize: 20 }} />}
        title={t('admin.ai_analyses.empty_title')}
      />
    );
  }
  return (
    <Bar
      data={rows}
      xField="count"
      yField="category"
      height={Math.max(220, rows.length * 32 + 40)}
      sort={{ reverse: false }}
      axis={{
        x: { title: t('admin.ai_analyses.axis_count') },
        y: { title: t('admin.ai_analyses.axis_category') },
      }}
    />
  );
}

function TopSubmittersChart({ rows }: { rows: AiAnalysisTopSubmitter[] }) {
  const { t } = useTranslation();
  if (rows.length === 0) {
    return (
      <EmptyState
        icon={<BarChartOutlined style={{ fontSize: 20 }} />}
        title={t('admin.ai_analyses.empty_title')}
      />
    );
  }
  const data = rows.map((r) => ({
    submitter: r.display_name ?? r.email,
    count: r.count,
  }));
  return (
    <Bar
      data={data}
      xField="count"
      yField="submitter"
      height={Math.max(220, rows.length * 32 + 40)}
      sort={{ reverse: false }}
      axis={{
        x: { title: t('admin.ai_analyses.axis_count') },
        y: { title: t('admin.ai_analyses.axis_submitter') },
      }}
    />
  );
}
