import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Card,
  DatePicker,
  Drawer,
  Input,
  Select,
  Skeleton,
  Space,
  Table,
} from 'antd';
import { Column } from '@ant-design/charts';
import type { Dayjs } from 'dayjs';
import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Avatar } from '@/components/common/Avatar';
import { AnomalyStatusPill } from '@/components/common/AnomalyStatusPill';
import {
  acknowledgeAnomaly,
  anomalyKeys,
  dismissAnomaly,
  listAnomalies,
} from '@/api/anomalies';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { adminErrorMessage } from '@/utils/apiErrors';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';
import { ANOMALY_STATUSES, anomalyStatusLabel, enumOptions } from '@/utils/enumLabels';
import { useWebSocket } from '@/hooks/useWebSocket';
import type { AnomalyListFilters, BehaviorAnomaly, BehaviorAnomalyStatus } from '@/types/api';

const PAGE_SIZE = 20;

export default function AnomaliesPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const { subscribe } = useWebSocket();

  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<BehaviorAnomalyStatus | 'all'>('OPEN');
  const [datasourceId, setDatasourceId] = useState<string>('all');
  const [userId, setUserId] = useState('');
  const [feature, setFeature] = useState('');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [detail, setDetail] = useState<BehaviorAnomaly | null>(null);

  useEffect(
    () =>
      subscribe('anomaly.detected', (data) => {
        message.warning(
          t('anomalies.toast_detected', { feature: data.feature, score: data.score.toFixed(1) }),
        );
      }),
    [subscribe, message, t],
  );

  const filters: AnomalyListFilters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      status: status === 'all' ? undefined : status,
      datasource_id: datasourceId === 'all' ? undefined : datasourceId,
      user_id: userId.trim() || undefined,
      feature: feature.trim() || undefined,
      from: range?.[0]?.toISOString(),
      to: range?.[1]?.toISOString(),
    }),
    [page, status, datasourceId, userId, feature, range],
  );

  const anomaliesQuery = useQuery({
    queryKey: anomalyKeys.list(filters),
    queryFn: () => listAnomalies(filters),
  });

  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ page: 0, size: 100 }),
    queryFn: () => listDatasources({ page: 0, size: 100 }),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: anomalyKeys.all });
  };

  const acknowledge = useMutation({
    mutationFn: (id: string) => acknowledgeAnomaly(id),
    onSuccess: () => {
      message.success(t('anomalies.acknowledge_success'));
      invalidate();
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const dismiss = useMutation({
    mutationFn: (id: string) => dismissAnomaly(id),
    onSuccess: () => {
      message.success(t('anomalies.dismiss_success'));
      invalidate();
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const confirmAcknowledge = (record: BehaviorAnomaly) => {
    modal.confirm({
      title: t('anomalies.acknowledge_confirm_title'),
      content: t('anomalies.acknowledge_confirm_body', { feature: record.feature }),
      okText: t('anomalies.action_acknowledge'),
      cancelText: t('common.cancel'),
      onOk: () => acknowledge.mutateAsync(record.id),
    });
  };

  const confirmDismiss = (record: BehaviorAnomaly) => {
    modal.confirm({
      title: t('anomalies.dismiss_confirm_title'),
      content: t('anomalies.dismiss_confirm_body', { feature: record.feature }),
      okText: t('anomalies.action_dismiss'),
      cancelText: t('common.cancel'),
      okButtonProps: { danger: true },
      onOk: () => dismiss.mutateAsync(record.id),
    });
  };

  const anomalies = anomaliesQuery.data?.content ?? [];

  // Aggregate the current page by feature for the chart.
  const byFeature = useMemo(() => {
    const counts = new Map<string, number>();
    for (const a of anomalies) {
      counts.set(a.feature, (counts.get(a.feature) ?? 0) + 1);
    }
    return [...counts.entries()].map(([f, count]) => ({ feature: f, count }));
  }, [anomalies]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('anomalies.title')}
        subtitle={t('anomalies.subtitle')}
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => anomaliesQuery.refetch()}>
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
        <Select
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          style={{ width: 180 }}
          data-testid="anomalies-status"
          options={[
            { value: 'all', label: t('anomalies.filter_status_all') },
            ...enumOptions(ANOMALY_STATUSES, anomalyStatusLabel, t),
          ]}
        />
        <Select
          value={datasourceId}
          onChange={(v) => {
            setDatasourceId(v);
            setPage(0);
          }}
          style={{ width: 240 }}
          showSearch
          optionFilterProp="label"
          loading={datasourcesQuery.isLoading}
          data-testid="anomalies-datasource"
          options={[
            { value: 'all', label: t('anomalies.filter_datasource_all') },
            ...(datasourcesQuery.data?.content ?? []).map((d) => ({ value: d.id, label: d.name })),
          ]}
        />
        <Input
          placeholder={t('anomalies.filter_user_placeholder')}
          value={userId}
          onChange={(e) => {
            setUserId(e.target.value);
            setPage(0);
          }}
          style={{ width: 240 }}
          className="mono"
        />
        <Input
          placeholder={t('anomalies.filter_feature_placeholder')}
          value={feature}
          onChange={(e) => {
            setFeature(e.target.value);
            setPage(0);
          }}
          style={{ width: 200 }}
          className="mono"
        />
        <DatePicker.RangePicker
          showTime
          value={range as [Dayjs | null, Dayjs | null]}
          onChange={(v) => {
            setRange(v as [Dayjs | null, Dayjs | null] | null);
            setPage(0);
          }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11, alignSelf: 'center' }}>
          {t('anomalies.count_label', { count: anomaliesQuery.data?.total_elements ?? 0 })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 28px' }}>
        {anomaliesQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 8 }} />
        ) : anomaliesQuery.isError ? (
          <EmptyState
            title={t('anomalies.load_error')}
            description={adminErrorMessage(anomaliesQuery.error)}
          />
        ) : anomalies.length === 0 ? (
          <EmptyState
            icon={<WarningOutlined style={{ fontSize: 20 }} />}
            title={t('anomalies.empty_title')}
            description={t('anomalies.empty_description')}
          />
        ) : (
          <Space orientation="vertical" size={20} style={{ width: '100%' }}>
            <Card title={t('anomalies.chart_by_feature_title')} data-testid="anomalies-feature-chart">
              <Column
                data={byFeature}
                xField="feature"
                yField="count"
                height={260}
                axis={{
                  x: { title: t('anomalies.axis_feature') },
                  y: { title: t('anomalies.axis_count') },
                }}
              />
            </Card>
            <Table<BehaviorAnomaly>
              rowKey="id"
              size="middle"
              dataSource={anomalies}
              scroll={{ x: 'max-content' }}
              pagination={{
                pageSize: PAGE_SIZE,
                current: page + 1,
                total: anomaliesQuery.data?.total_elements ?? 0,
                onChange: (p) => setPage(p - 1),
              }}
              onRow={(record) => ({
                onClick: () => setDetail(record),
                style: { cursor: 'pointer' },
              })}
              columns={[
                {
                  title: t('anomalies.col_detected'),
                  dataIndex: 'detected_at',
                  width: 120,
                  render: (v) => <span className="muted">{timeAgo(v)}</span>,
                },
                {
                  title: t('anomalies.col_user'),
                  width: 220,
                  render: (_v, a) => {
                    const name =
                      userDisplay(a.user_display_name, a.user_email) ||
                      t('anomalies.user_unknown');
                    return (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <Avatar name={name} size={20} />
                        <div>
                          <div style={{ fontSize: 12 }}>{name}</div>
                          {a.user_email && (
                            <div className="mono muted" style={{ fontSize: 10 }}>
                              {a.user_email}
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  },
                },
                {
                  title: t('anomalies.col_datasource'),
                  dataIndex: 'datasource_name',
                  width: 160,
                  render: (v: string | null) =>
                    v ? <span className="mono" style={{ fontSize: 11.5 }}>{v}</span> : <span className="muted">—</span>,
                },
                {
                  title: t('anomalies.col_feature'),
                  dataIndex: 'feature',
                  width: 180,
                  render: (v) => (
                    <span className="mono" style={{ fontSize: 11.5, fontWeight: 500 }}>
                      {v}
                    </span>
                  ),
                },
                {
                  title: t('anomalies.col_score'),
                  dataIndex: 'score',
                  width: 90,
                  render: (v: number) => (
                    <span className="mono" style={{ fontWeight: 600 }}>
                      {v.toFixed(1)}
                    </span>
                  ),
                },
                {
                  title: t('anomalies.col_status'),
                  dataIndex: 'status',
                  width: 130,
                  render: (v: BehaviorAnomalyStatus) => <AnomalyStatusPill status={v} size="sm" />,
                },
                {
                  title: t('anomalies.col_summary'),
                  dataIndex: 'ai_summary',
                  render: (v: string | null) =>
                    v ? (
                      <span style={{ fontSize: 12 }}>
                        {v.length > 90 ? `${v.slice(0, 90)}…` : v}
                      </span>
                    ) : (
                      <span className="muted">—</span>
                    ),
                },
                {
                  title: t('anomalies.col_actions'),
                  width: 200,
                  render: (_v, a) => (
                    <Space size={4} onClick={(e) => e.stopPropagation()}>
                      <Button
                        size="small"
                        disabled={a.status !== 'OPEN'}
                        loading={acknowledge.isPending && acknowledge.variables === a.id}
                        onClick={() => confirmAcknowledge(a)}
                        data-testid={`acknowledge-${a.id}`}
                      >
                        {t('anomalies.action_acknowledge')}
                      </Button>
                      <Button
                        size="small"
                        danger
                        disabled={a.status === 'DISMISSED'}
                        loading={dismiss.isPending && dismiss.variables === a.id}
                        onClick={() => confirmDismiss(a)}
                        data-testid={`dismiss-${a.id}`}
                      >
                        {t('anomalies.action_dismiss')}
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />
          </Space>
        )}
      </div>
      <Drawer
        open={!!detail}
        onClose={() => setDetail(null)}
        title={t('anomalies.drawer_title')}
        width={520}
      >
        {detail && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <DetailCard>
              <Space orientation="vertical" size={6} style={{ width: '100%' }}>
                <div
                  className="mono"
                  style={{ fontSize: 14, fontWeight: 600, color: 'var(--risk-crit)' }}
                >
                  {detail.feature}
                </div>
                <AnomalyStatusPill status={detail.status} size="sm" />
              </Space>
            </DetailCard>
            <DetailCard title={t('anomalies.section_signal')}>
              <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
                <Row k={t('anomalies.field_score')} v={detail.score.toFixed(2)} />
                <Row k={t('anomalies.field_observed')} v={fmtNum(detail.observed_value)} />
                <Row k={t('anomalies.field_baseline_mean')} v={fmtNum(detail.baseline_mean)} />
                <Row k={t('anomalies.field_baseline_stddev')} v={fmtNum(detail.baseline_stddev)} />
                <Row k={t('anomalies.field_window')} v={`${fmtDate(detail.window_start)} → ${fmtDate(detail.window_end)}`} />
                <Row k={t('anomalies.field_detected')} v={fmtDate(detail.detected_at)} />
                {detail.acknowledged_at && (
                  <Row k={t('anomalies.field_acknowledged')} v={fmtDate(detail.acknowledged_at)} />
                )}
              </div>
            </DetailCard>
            {detail.ai_summary && (
              <DetailCard title={t('anomalies.section_summary')}>
                <div style={{ padding: 14, fontSize: 13, lineHeight: 1.6 }}>{detail.ai_summary}</div>
              </DetailCard>
            )}
            <DetailCard title={t('anomalies.section_detail')}>
              <pre
                style={{
                  margin: 0,
                  padding: 14,
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11.5,
                  lineHeight: 1.5,
                  background: 'var(--bg-code)',
                  borderRadius: '0 0 var(--radius-md) var(--radius-md)',
                  overflow: 'auto',
                }}
              >
                {JSON.stringify(detail.detail ?? {}, null, 2)}
              </pre>
            </DetailCard>
          </div>
        )}
      </Drawer>
    </div>
  );
}

function DetailCard({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      {title && (
        <div
          style={{
            padding: '10px 14px',
            borderBottom: '1px solid var(--border)',
            fontWeight: 600,
            fontSize: 12,
          }}
        >
          {title}
        </div>
      )}
      {!title ? <div style={{ padding: 14 }}>{children}</div> : children}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        gap: 8,
        fontFamily: 'var(--font-mono)',
        fontSize: 12,
      }}
    >
      <span className="muted">{k}</span>
      <span style={{ textAlign: 'right' }}>{v}</span>
    </div>
  );
}
