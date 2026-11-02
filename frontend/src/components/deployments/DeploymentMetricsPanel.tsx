import { useMemo, useState, type ReactNode } from 'react';
import { Card, DatePicker, Select, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { BarChartOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { Dayjs } from 'dayjs';
import { EmptyState } from '@/components/common/EmptyState';
import {
  Area,
  AreaChart,
  ChartTooltip,
  Grid,
  RingCenter,
  RingChart,
  XAxis,
  type RingData,
} from '@/components/charts';
import {
  deploymentMetricKeys,
  getDeploymentEnvironmentMetrics,
  getOrganizationDeploymentMetrics,
  getPipelineDeploymentMetrics,
} from '@/api/deploymentMetrics';
import { deploymentPipelineKeys, listDeploymentPipelines } from '@/api/deploymentPipelines';
import {
  deploymentVersionKeys,
  listPipelineEnvironmentVersions,
} from '@/api/deploymentVersions';
import {
  METRICS_PERIOD_PRESETS,
  formatPerDay,
  formatRate,
  formatSeconds,
  isBeyondMaxWindow,
  periodWindow,
  presetRange,
  type MetricsPeriodPreset,
} from '@/pages/deployments/deploymentMetricsFilters';
import { apiErrorMessage } from '@/utils/apiErrors';
import { leadTimeSourceLabel } from '@/utils/enumLabels';
import { usePermission } from '@/utils/permissions';
import { deploymentOutcomeColor, statusColor } from '@/utils/statusColors';
import type { DeploymentEnvironmentMetrics, DeploymentMetrics } from '@/types/api';
import { MetricTile } from './MetricTile';

// Not a plausible environment or pipeline name, so it can never shadow a real option.
const ALL = '__all__';

interface DeploymentMetricsPanelProps {
  /** Fixed pipeline (settings tab, per-pipeline route); omitted ⇒ the org-wide view. */
  pipelineId?: string;
}

/**
 * DORA + approval-SLA metrics (#987) over the #984 read API: tiles, a daily area chart, a
 * change-failure ring and — org-wide only — the per-environment breakdown. Every number comes
 * from the server; this component only formats.
 */
export function DeploymentMetricsPanel({ pipelineId }: DeploymentMetricsPanelProps) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const orgWide = !pipelineId;
  const canManagePipelines = usePermission('DEPLOYMENT_PIPELINE_MANAGE');

  const [preset, setPreset] = useState<MetricsPeriodPreset>('30d');
  const [customRange, setCustomRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [environment, setEnvironment] = useState<string>(ALL);
  const [pipeline, setPipeline] = useState<string>(ALL);

  const period = useMemo(() => periodWindow(preset, new Date(), customRange), [preset, customRange]);
  const environmentFilter = environment === ALL ? undefined : environment;
  const scope = useMemo(
    () => ({
      from: period.from,
      to: period.to,
      pipeline_id: orgWide && pipeline !== ALL ? pipeline : undefined,
    }),
    [period, orgWide, pipeline],
  );

  const metricsQuery = useQuery({
    queryKey: pipelineId
      ? deploymentMetricKeys.pipeline(pipelineId, {
          from: scope.from,
          to: scope.to,
          environment: environmentFilter,
        })
      : deploymentMetricKeys.organization({ ...scope, environment: environmentFilter }),
    queryFn: () =>
      pipelineId
        ? getPipelineDeploymentMetrics(pipelineId, {
            from: scope.from,
            to: scope.to,
            environment: environmentFilter,
          })
        : getOrganizationDeploymentMetrics({ ...scope, environment: environmentFilter }),
  });

  // Fetched without the environment filter: it also feeds the environment options, which would
  // otherwise collapse to the selected name.
  const breakdownQuery = useQuery({
    queryKey: deploymentMetricKeys.breakdown(scope),
    queryFn: () => getDeploymentEnvironmentMetrics(scope),
    enabled: orgWide,
  });

  // GET /deployment-pipelines is admin-only; everyone else filters by environment name.
  const pipelinesQuery = useQuery({
    queryKey: deploymentPipelineKeys.list({ size: 100 }),
    queryFn: () => listDeploymentPipelines({ size: 100 }),
    enabled: orgWide && canManagePipelines,
  });

  // The per-pipeline matrix is open to can_trigger holders, unlike the breakdown.
  const matrixQuery = useQuery({
    queryKey: deploymentVersionKeys.matrix(pipelineId ?? ''),
    queryFn: () => listPipelineEnvironmentVersions(pipelineId ?? ''),
    enabled: !orgWide,
  });

  const environmentOptions = useMemo(() => {
    const names = orgWide
      ? (breakdownQuery.data?.environments ?? []).map((e) => e.environment_name)
      : (matrixQuery.data ?? []).map((row) => row.environment.name);
    if (environment !== ALL) names.push(environment);
    const seen = new Map<string, string>();
    for (const name of names) {
      if (!seen.has(name.toLowerCase())) seen.set(name.toLowerCase(), name);
    }
    return [...seen.values()]
      .sort((a, b) => a.localeCompare(b))
      .map((name) => ({ value: name, label: name }));
  }, [orgWide, breakdownQuery.data, matrixQuery.data, environment]);

  const breakdownRows = useMemo(() => {
    const rows = breakdownQuery.data?.environments ?? [];
    if (!environmentFilter) return rows;
    const wanted = environmentFilter.toLowerCase();
    return rows.filter((row) => row.environment_name.toLowerCase() === wanted);
  }, [breakdownQuery.data, environmentFilter]);

  const filters = (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
      <Select<MetricsPeriodPreset>
        value={preset}
        aria-label={t('deploygov.metrics.period')}
        onChange={(value) => {
          if (value === 'custom' && !customRange) {
            setCustomRange(presetRange(preset === 'custom' ? '30d' : preset, new Date()));
          }
          setPreset(value);
        }}
        options={METRICS_PERIOD_PRESETS.map((value) => ({
          value,
          label: t(`deploygov.metrics.period_${value}`),
        }))}
        style={{ width: 160 }}
      />
      {preset === 'custom' && (
        <DatePicker.RangePicker
          aria-label={t('deploygov.metrics.period_custom')}
          value={customRange}
          allowClear={false}
          disabledDate={(current, info) => isBeyondMaxWindow(current, info?.from)}
          onChange={(range) => {
            if (range?.[0] && range[1]) setCustomRange([range[0], range[1]]);
          }}
        />
      )}
      {orgWide && canManagePipelines && (
        <Select
          value={pipeline}
          aria-label={t('deploygov.metrics.pipeline')}
          onChange={setPipeline}
          options={[
            { value: ALL, label: t('deploygov.metrics.allPipelines') },
            ...(pipelinesQuery.data?.content ?? []).map((p) => ({ value: p.id, label: p.name })),
          ]}
          style={{ width: 200 }}
        />
      )}
      <Select
        value={environment}
        aria-label={t('deploygov.metrics.environment')}
        showSearch
        onChange={setEnvironment}
        options={[{ value: ALL, label: t('deploygov.metrics.allEnvironments') }, ...environmentOptions]}
        style={{ width: 200 }}
      />
    </div>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {filters}
      <MetricsBody
        query={metricsQuery}
        locale={locale}
        breakdown={
          orgWide ? (
            <BreakdownTable
              rows={breakdownRows}
              loading={breakdownQuery.isLoading}
              error={breakdownQuery.isError ? breakdownQuery.error : null}
              locale={locale}
            />
          ) : null
        }
      />
    </div>
  );
}

interface MetricsBodyProps {
  query: { data?: DeploymentMetrics; isLoading: boolean; isError: boolean; error: unknown };
  locale: string;
  breakdown: ReactNode;
}

function MetricsBody({ query, locale, breakdown }: MetricsBodyProps) {
  const { t } = useTranslation();
  if (query.isLoading) {
    return <Skeleton active paragraph={{ rows: 8 }} />;
  }
  if (query.isError || !query.data) {
    return (
      <EmptyState
        title={t('deploygov.error')}
        description={apiErrorMessage(query.error, () => t('deploygov.error'))}
      />
    );
  }
  const data = query.data;
  if (data.deployment_frequency.total === 0) {
    return (
      <EmptyState
        icon={<BarChartOutlined />}
        title={t('deploygov.metrics.empty')}
        description={t('deploygov.metrics.emptyDescription')}
      />
    );
  }
  return (
    <>
      <MetricTiles data={data} locale={locale} />
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <Card size="small" title={t('deploygov.metrics.dailyTitle')} style={{ flex: '2 1 420px' }}>
          <DailyChart data={data} />
        </Card>
        <Card size="small" title={t('deploygov.metrics.ringTitle')} style={{ flex: '1 1 280px' }}>
          <FailureRing data={data} locale={locale} />
        </Card>
      </div>
      {breakdown}
    </>
  );
}

function MetricTiles({ data, locale }: { data: DeploymentMetrics; locale: string }) {
  const { t } = useTranslation();
  const lead = data.lead_time;
  const cfr = data.change_failure_rate;
  const restore = data.time_to_restore;
  const approval = data.approval_latency;
  const timeout = data.timeout_rate;
  return (
    <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fill, minmax(210px, 1fr))' }}>
      <MetricTile
        testId="metric-frequency"
        label={t('deploygov.metrics.frequency')}
        value={data.deployment_frequency.total}
        caption={t('deploygov.metrics.perDay', {
          value: formatPerDay(data.deployment_frequency.per_day, locale),
        })}
      />
      <MetricTile
        testId="metric-lead-time"
        label={t('deploygov.metrics.leadTime')}
        value={formatSeconds(lead.p50_seconds)}
        caption={
          <>
            <span>{t('deploygov.metrics.p95', { value: formatSeconds(lead.p95_seconds) })}</span>
            {lead.source && (
              <span data-testid="metric-lead-time-source">{leadTimeSourceLabel(t, lead.source)}</span>
            )}
          </>
        }
      />
      <MetricTile
        testId="metric-change-failure"
        label={t('deploygov.metrics.changeFailureRate')}
        value={formatRate(cfr.rate, locale)}
        caption={
          <>
            <span>
              {t('deploygov.metrics.changeFailureDetail', {
                failed: cfr.failed,
                rolledBack: cfr.rolled_back,
                executed: cfr.executed,
              })}
            </span>
            {cfr.unreported > 0 && (
              <span data-testid="metric-unreported">
                {t('deploygov.metrics.unreported', { n: cfr.unreported })}
              </span>
            )}
          </>
        }
      />
      <MetricTile
        testId="metric-restore"
        label={t('deploygov.metrics.timeToRestore')}
        value={formatSeconds(restore.p50_seconds)}
        caption={
          <>
            <span>{t('deploygov.metrics.p95', { value: formatSeconds(restore.p95_seconds) })}</span>
            <span>{t('deploygov.metrics.openIncidents', { n: restore.open_incidents })}</span>
          </>
        }
      />
      <MetricTile
        testId="metric-approval"
        label={t('deploygov.metrics.approvalLatency')}
        value={formatSeconds(approval.p50_seconds)}
        caption={
          <>
            <span>{t('deploygov.metrics.p95', { value: formatSeconds(approval.p95_seconds) })}</span>
            <span>{t('deploygov.metrics.sampleSize', { n: approval.sample_size })}</span>
          </>
        }
      />
      <MetricTile
        testId="metric-timeout"
        label={t('deploygov.metrics.timeoutRate')}
        value={formatRate(timeout.rate, locale)}
        caption={t('deploygov.metrics.timeoutDetail', {
          timedOut: timeout.timed_out,
          enteredReview: timeout.entered_review,
        })}
      />
      <MetricTile
        testId="metric-break-glass"
        label={t('deploygov.metrics.breakGlass')}
        value={data.break_glass_count}
        caption={t('deploygov.metrics.breakGlassDetail')}
      />
    </div>
  );
}

function DailyChart({ data }: { data: DeploymentMetrics }) {
  const { t } = useTranslation();
  const series = [
    { label: t('deploygov.metrics.seriesDeployments'), color: statusColor('EXECUTED').fg },
    { label: t('deploygov.metrics.seriesFailed'), color: deploymentOutcomeColor('FAILED').fg },
    { label: t('deploygov.metrics.seriesRolledBack'), color: deploymentOutcomeColor('ROLLED_BACK').fg },
  ] as const;
  const rows = data.daily.map((day) => ({
    date: new Date(`${day.date}T00:00:00Z`),
    [series[0].label]: day.deployments,
    [series[1].label]: day.failed,
    [series[2].label]: day.rolled_back,
  }));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {/* An aspect-ratio box takes a content-based min-height, so the rendered SVG would feed its
          own height back into the box on every resize; minHeight: 0 keeps the ratio authoritative. */}
      <AreaChart data={rows} aspectRatio="2.6 / 1" style={{ minHeight: 0 }}>
        <Grid horizontal />
        {series.map((s) => (
          <Area
            key={s.label}
            dataKey={s.label}
            fill={s.color}
            stroke={s.color}
            strokeWidth={2}
            fillOpacity={0.25}
          />
        ))}
        <XAxis />
        <ChartTooltip />
      </AreaChart>
      <Legend items={series.map((s) => ({ label: s.label, color: s.color }))} />
    </div>
  );
}

function FailureRing({ data, locale }: { data: DeploymentMetrics; locale: string }) {
  const { t } = useTranslation();
  const cfr = data.change_failure_rate;
  const rings: RingData[] = [
    {
      label: t('deploygov.metrics.seriesFailed'),
      value: cfr.failed,
      maxValue: cfr.executed,
      color: deploymentOutcomeColor('FAILED').fg,
    },
    {
      label: t('deploygov.metrics.seriesRolledBack'),
      value: cfr.rolled_back,
      maxValue: cfr.executed,
      color: deploymentOutcomeColor('ROLLED_BACK').fg,
    },
  ];
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap' }}>
      <div style={{ width: 170, height: 170, flex: 'none' }}>
        {/* geometryScrubbing draws the arcs straight from `data`; without it or explicit <Ring>
            children the vendored chart renders an empty SVG. RingCenter shows the failed count
            (it only renders custom content on hover), so the server's rate sits beside it. */}
        <RingChart data={rings} strokeWidth={10} ringGap={5} baseInnerRadius={46} geometryScrubbing>
          <RingCenter defaultLabel={t('deploygov.metrics.ringTotal')} />
        </RingChart>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div data-testid="metric-ring-rate">
          <div className="mono" style={{ fontSize: 20, fontWeight: 600 }}>
            {formatRate(cfr.rate, locale)}
          </div>
          <div className="muted" style={{ fontSize: 11 }}>
            {t('deploygov.metrics.ringCenter')}
          </div>
        </div>
        <Legend items={rings.map((r) => ({ label: `${r.label} · ${r.value}`, color: r.color ?? '' }))} />
      </div>
    </div>
  );
}

function Legend({ items }: { items: Array<{ label: string; color: string }> }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 14px' }}>
      {items.map((item) => (
        <span
          key={item.label}
          className="muted"
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}
        >
          <span
            aria-hidden
            style={{
              width: 8,
              height: 8,
              borderRadius: '50%',
              background: item.color,
              display: 'inline-block',
            }}
          />
          {item.label}
        </span>
      ))}
    </div>
  );
}

interface BreakdownTableProps {
  rows: DeploymentEnvironmentMetrics[];
  loading: boolean;
  error: unknown;
  locale: string;
}

function BreakdownTable({ rows, loading, error, locale }: BreakdownTableProps) {
  const { t } = useTranslation();
  const columns: TableColumnsType<DeploymentEnvironmentMetrics> = [
    {
      title: t('deploygov.metrics.colPipeline'),
      dataIndex: 'pipeline_name',
      render: (name: string, row) => <Link to={`/deployment-versions/${row.pipeline_id}`}>{name}</Link>,
    },
    { title: t('deploygov.metrics.colEnvironment'), dataIndex: 'environment_name' },
    {
      title: t('deploygov.metrics.colDeployments'),
      key: 'deployments',
      align: 'right',
      render: (_v, row) => row.deployment_frequency.total,
    },
    {
      title: t('deploygov.metrics.colChangeFailure'),
      key: 'cfr',
      align: 'right',
      render: (_v, row) => formatRate(row.change_failure_rate.rate, locale),
    },
    {
      title: t('deploygov.metrics.colLeadTime'),
      key: 'lead',
      align: 'right',
      render: (_v, row) => formatSeconds(row.lead_time.p50_seconds),
    },
    {
      title: t('deploygov.metrics.colRestore'),
      key: 'restore',
      align: 'right',
      render: (_v, row) => formatSeconds(row.time_to_restore.p50_seconds),
    },
    {
      title: t('deploygov.metrics.colApproval'),
      key: 'approval',
      align: 'right',
      render: (_v, row) => formatSeconds(row.approval_latency.p50_seconds),
    },
    {
      title: t('deploygov.metrics.colTimeouts'),
      key: 'timeouts',
      align: 'right',
      render: (_v, row) => row.timeout_rate.timed_out,
    },
  ];
  return (
    <Card size="small" title={t('deploygov.metrics.breakdownTitle')} data-testid="metrics-breakdown">
      {error ? (
        <EmptyState
          size="sm"
          title={t('deploygov.error')}
          description={apiErrorMessage(error, () => t('deploygov.error'))}
        />
      ) : (
        <Table<DeploymentEnvironmentMetrics>
          rowKey="environment_id"
          dataSource={rows}
          columns={columns}
          loading={loading}
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: t('deploygov.metrics.breakdownEmpty') }}
        />
      )}
    </Card>
  );
}
