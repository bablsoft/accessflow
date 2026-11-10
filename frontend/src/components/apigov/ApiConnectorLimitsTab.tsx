import { useMemo, useState } from 'react';
import { App, Button, Card, Form, InputNumber, Segmented, Tag } from 'antd';
import { LineChartOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { WidgetError } from '@/components/dashboard/WidgetError';
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
import { apiConnectorKeys, getApiConnector, updateApiConnector } from '@/api/apiConnectors';
import { apiConnectorUsageKeys, getApiConnectorUsage } from '@/api/apiConnectorUsage';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { formatMicros, fromMicros, toMicros } from '@/utils/micros';
import { statusColor } from '@/utils/statusColors';
import {
  CONNECTOR_USAGE_RANGES,
  connectorUsageRows,
  connectorUsageWindow,
  type ConnectorUsageRange,
} from '@/utils/connectorUsage';
import type { ApiConnector, ApiConnectorUsage, UpdateApiConnectorInput } from '@/types/api';

interface ApiConnectorLimitsTabProps {
  connectorId: string;
}

interface LimitsFormValues {
  calls_per_minute?: number | null;
  calls_per_day?: number | null;
  per_user_share_percent?: number | null;
  /** Edited in units; stored as micros. */
  cost_per_call?: number | null;
  monthly_budget?: number | null;
}

const FULL_WIDTH = { width: '100%' } as const;

function initialValues(c: ApiConnector): LimitsFormValues {
  return {
    calls_per_minute: c.calls_per_minute ?? null,
    calls_per_day: c.calls_per_day ?? null,
    per_user_share_percent: c.per_user_share_percent ?? null,
    cost_per_call: fromMicros(c.cost_per_call_micros),
    monthly_budget: fromMicros(c.monthly_budget_micros),
  };
}

/**
 * Every field is always sent: an empty input sends 0 (clear → unlimited), so the form is the
 * single source of truth for the five columns. Nothing here sends null (= unchanged).
 */
function limitsPayload(values: LimitsFormValues): UpdateApiConnectorInput {
  return {
    calls_per_minute: values.calls_per_minute ?? 0,
    calls_per_day: values.calls_per_day ?? 0,
    per_user_share_percent: values.per_user_share_percent ?? 0,
    cost_per_call_micros: toMicros(values.cost_per_call) ?? 0,
    monthly_budget_micros: toMicros(values.monthly_budget) ?? 0,
  };
}

/** The backend zero-fills every day of the window, so "empty" means no activity on any day. */
function noActivity(usage: ApiConnectorUsage | undefined): boolean {
  return (usage?.days ?? []).every(
    (d) => d.calls === 0 && d.failed_calls === 0 && d.throttled_calls === 0,
  );
}

/** Rate limits, per-user share, per-call price and monthly budget for one connector (#974). */
export function ApiConnectorLimitsTab({ connectorId }: ApiConnectorLimitsTabProps) {
  const { t } = useTranslation();
  const connectorQuery = useQuery({
    queryKey: apiConnectorKeys.detail(connectorId),
    queryFn: () => getApiConnector(connectorId),
  });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card size="small" title={t('apiGov.limits.title')}>
        {connectorQuery.data && <LimitsForm connector={connectorQuery.data} />}
      </Card>
      <Card size="small" title={t('apiGov.usage.title')}>
        <UsagePanel connectorId={connectorId} />
      </Card>
    </div>
  );
}

function LimitsForm({ connector }: { connector: ApiConnector }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<LimitsFormValues>();
  const mutation = useMutation({
    mutationFn: (input: UpdateApiConnectorInput) => updateApiConnector(connector.id, input),
    onSuccess: () => {
      message.success(t('apiGov.limits.saved'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connector.id) });
      // The list's Limits badge reads the same row.
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.lists() });
      queryClient.invalidateQueries({ queryKey: apiConnectorUsageKeys.connector(connector.id) });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  const unlimited = t('apiGov.limits.emptyUnlimited');
  return (
    <Form<LimitsFormValues>
      form={form}
      name="connector-limits"
      layout="vertical"
      style={{ maxWidth: 560 }}
      initialValues={initialValues(connector)}
      onFinish={(values) => mutation.mutate(limitsPayload(values))}
    >
      <Form.Item
        name="calls_per_minute"
        label={t('apiGov.limits.callsPerMinute')}
        extra={unlimited}
        rules={[{ type: 'number', min: 0, message: t('apiGov.limits.minZero') }]}
      >
        <InputNumber min={0} precision={0} style={FULL_WIDTH} />
      </Form.Item>
      <Form.Item
        name="calls_per_day"
        label={t('apiGov.limits.callsPerDay')}
        extra={unlimited}
        rules={[{ type: 'number', min: 0, message: t('apiGov.limits.minZero') }]}
      >
        <InputNumber min={0} precision={0} style={FULL_WIDTH} />
      </Form.Item>
      <Form.Item
        name="per_user_share_percent"
        label={t('apiGov.limits.perUserShare')}
        extra={t('apiGov.limits.perUserShareHint')}
        rules={[{ type: 'number', min: 0, max: 100, message: t('apiGov.limits.shareRange') }]}
      >
        <InputNumber precision={0} suffix="%" style={FULL_WIDTH} />
      </Form.Item>
      <Form.Item
        name="cost_per_call"
        label={t('apiGov.limits.costPerCall')}
        extra={t('apiGov.limits.costHint')}
        rules={[{ type: 'number', min: 0, message: t('apiGov.limits.minZero') }]}
      >
        <InputNumber min={0} precision={6} step={0.01} style={FULL_WIDTH} />
      </Form.Item>
      <Form.Item
        name="monthly_budget"
        label={t('apiGov.limits.monthlyBudget')}
        extra={t('apiGov.limits.budgetHint')}
        rules={[{ type: 'number', min: 0, message: t('apiGov.limits.minZero') }]}
      >
        <InputNumber min={0} precision={6} step={1} style={FULL_WIDTH} />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={mutation.isPending}>
        {t('apiGov.limits.save')}
      </Button>
    </Form>
  );
}

function UsagePanel({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const [range, setRange] = useState<ConnectorUsageRange>('month');
  const usageWindow = useMemo(() => connectorUsageWindow(range, new Date()), [range]);
  const usageQuery = useQuery({
    queryKey: apiConnectorUsageKeys.usage(connectorId, usageWindow.from, usageWindow.to),
    queryFn: () => getApiConnectorUsage(connectorId, usageWindow),
  });
  const series = useMemo(
    () => ({
      calls: t('apiGov.usage.seriesCalls'),
      failed: t('apiGov.usage.seriesFailed'),
      throttled: t('apiGov.usage.seriesThrottled'),
    }),
    [t],
  );
  const colors = useMemo(
    () => [
      { label: series.calls, color: statusColor('EXECUTED').fg },
      { label: series.failed, color: statusColor('FAILED').fg },
      // Warning colour, not APPROVED: APPROVED and EXECUTED share green, and a throttled call is
      // the same queued state the request list marks with the warn-coloured hourglass.
      { label: series.throttled, color: statusColor('TIMED_OUT').fg },
    ],
    [series],
  );
  const rows = useMemo(
    () => connectorUsageRows(usageQuery.data?.days ?? [], usageWindow, series),
    [usageQuery.data, usageWindow, series],
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Segmented<ConnectorUsageRange>
        size="small"
        value={range}
        onChange={setRange}
        style={{ alignSelf: 'flex-start' }}
        options={CONNECTOR_USAGE_RANGES.map((value) => ({
          value,
          label: t(`apiGov.usage.range_${value}` as const),
        }))}
      />
      {usageQuery.isError ? (
        <WidgetError
          error={usageQuery.error}
          onRetry={() => void usageQuery.refetch()}
          messageFor={(e) => apiErrorMessage(e, () => t('apiGov.error'))}
        />
      ) : (
        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <div style={{ flex: '1 1 420px', minWidth: 0 }}>
            {!usageQuery.isLoading && noActivity(usageQuery.data) ? (
              <EmptyState
                size="sm"
                icon={<LineChartOutlined style={{ fontSize: 16 }} />}
                title={t('apiGov.usage.empty')}
              />
            ) : (
              <>
                <AreaChart
                  data={rows}
                  aspectRatio="2.6 / 1"
                  status={usageQuery.isLoading ? 'loading' : 'ready'}
                  loadingLabel={t('apiGov.usage.loading')}
                >
                  <Grid horizontal />
                  {colors.map(({ label, color }) => (
                    <Area
                      key={label}
                      dataKey={label}
                      fill={color}
                      stroke={color}
                      strokeWidth={2}
                      fillOpacity={0.2}
                    />
                  ))}
                  <XAxis />
                  <ChartTooltip />
                </AreaChart>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 14px', marginTop: 8 }}>
                  {colors.map(({ label, color }) => (
                    <span
                      key={label}
                      className="muted"
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}
                    >
                      <span
                        aria-hidden
                        style={{
                          width: 8,
                          height: 8,
                          borderRadius: '50%',
                          background: color,
                          display: 'inline-block',
                        }}
                      />
                      {label}
                    </span>
                  ))}
                </div>
              </>
            )}
          </div>
          {usageQuery.data && <BudgetSummary usage={usageQuery.data} />}
        </div>
      )}
    </div>
  );
}

function BudgetSummary({ usage }: { usage: ApiConnectorUsage }) {
  const { t, i18n } = useTranslation();
  const percent = usage.budget_used_percent ?? null;
  const spend = formatMicros(usage.month_to_date_spend_micros, i18n.language);
  if (percent === null) {
    return (
      <div style={{ minWidth: 190 }} data-testid="connector-usage-spend">
        <div className="muted" style={{ fontSize: 12 }}>
          {t('apiGov.usage.monthToDate')}
        </div>
        <div style={{ fontSize: 20, fontWeight: 600 }}>{t('apiGov.usage.units', { value: spend })}</div>
        <div className="muted" style={{ fontSize: 12 }}>
          {usage.monthly_budget_micros != null
            ? t('apiGov.usage.noPrice')
            : t('apiGov.usage.noBudget')}
        </div>
      </div>
    );
  }
  const exceeded = percent >= 100;
  const ring: RingData[] = [
    {
      label: t('apiGov.usage.budgetUsed'),
      value: Math.min(percent, 100),
      maxValue: 100,
      color: statusColor(exceeded ? 'FAILED' : 'EXECUTED').fg,
    },
  ];
  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, minWidth: 190 }}
      data-testid="connector-usage-budget"
    >
      <div style={{ width: 170, height: 170 }}>
        {/* geometryScrubbing: the vendored RingChart draws arcs only in this mode or from
            explicit <Ring> children, which the barrel does not export. */}
        <RingChart data={ring} strokeWidth={12} baseInnerRadius={52} geometryScrubbing>
          <RingCenter defaultLabel={t('apiGov.usage.budgetUsed')} suffix="%" />
        </RingChart>
      </div>
      <span>{t('apiGov.usage.percentUsed', { percent })}</span>
      <span className="muted" style={{ fontSize: 12 }}>
        {t('apiGov.usage.spendOfBudget', {
          spend,
          budget: formatMicros(usage.monthly_budget_micros, i18n.language),
        })}
      </span>
      {exceeded && <Tag color="error">{t('apiGov.usage.budgetExceeded')}</Tag>}
      <span className="muted" style={{ fontSize: 11, textAlign: 'center', maxWidth: 220 }}>
        {t('apiGov.usage.budgetNeverBlocks')}
      </span>
    </div>
  );
}
