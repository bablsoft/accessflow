import { useMemo, useState } from 'react';
import { App, Button, DatePicker, Input, Select, Skeleton, Table, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import type { Dayjs } from 'dayjs';
import { ClockCircleOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import {
  apiRequestKeys,
  cancelApiRequest,
  executeApiRequest,
  listApiRequests,
} from '@/api/apiRequests';
import { apiConnectorKeys, listApiConnectors } from '@/api/apiConnectors';
import { enumOptions, queryStatusLabel, riskLevelLabel } from '@/utils/enumLabels';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import type { ApiRequest, QueryStatus, RiskLevel } from '@/types/api';

const STATUSES: QueryStatus[] = [
  'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'TIMED_OUT', 'FAILED', 'CANCELLED',
];
const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const VERBS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

const PAGE_SIZE = 20;

export default function ApiRequestsListPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [q, setQ] = useState('');
  const [status, setStatus] = useState<QueryStatus | 'all'>('all');
  const [connector, setConnector] = useState<string | 'all'>('all');
  const [verb, setVerb] = useState<string | 'all'>('all');
  const [risk, setRisk] = useState<RiskLevel | 'all'>('all');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [page, setPage] = useState(0);

  const filters = useMemo(
    () => ({
      status: status === 'all' ? undefined : status,
      connector_id: connector === 'all' ? undefined : connector,
      verb: verb === 'all' ? undefined : verb,
      from: range?.[0] ? range[0].toISOString() : undefined,
      to: range?.[1] ? range[1].endOf('day').toISOString() : undefined,
      page,
      size: PAGE_SIZE,
    }),
    [status, connector, verb, range, page],
  );

  const requestsQuery = useQuery({
    queryKey: apiRequestKeys.list(filters),
    queryFn: () => listApiRequests(filters),
  });

  const connectorsQuery = useQuery({
    queryKey: apiConnectorKeys.list({ size: 100 }),
    queryFn: () => listApiConnectors({ size: 100 }),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: apiRequestKeys.lists() });

  const cancelMutation = useMutation({
    mutationFn: cancelApiRequest,
    onSuccess: () => {
      message.success(t('apiGov.requests.cancelled'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const executeMutation = useMutation({
    mutationFn: executeApiRequest,
    onSuccess: () => {
      message.success(t('apiGov.requests.executed'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const rows = useMemo(() => requestsQuery.data?.content ?? [], [requestsQuery.data]);

  // Free-text + risk filter happen client-side over the current page (the server has no full-text
  // filter, and risk lives on the embedded ai_analysis snapshot, which is not server-filterable).
  const filtered = useMemo(
    () =>
      rows.filter((r) => {
        if (risk !== 'all' && r.ai_risk_level !== risk) return false;
        if (q) {
          const n = q.toLowerCase();
          if (
            !r.id.toLowerCase().includes(n) &&
            !(r.connector_name ?? '').toLowerCase().includes(n) &&
            !r.request_path.toLowerCase().includes(n)
          ) {
            return false;
          }
        }
        return true;
      }),
    [rows, q, risk],
  );

  const cols: TableColumnsType<ApiRequest> = [
    {
      title: t('apiGov.requests.id'),
      dataIndex: 'id',
      width: 90,
      render: (v: string) => <span className="mono muted" style={{ fontSize: 12 }}>{v.slice(0, 8)}</span>,
    },
    {
      title: t('apiGov.requests.connector'),
      width: 160,
      render: (_v, r) => (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 13 }}>{r.connector_name ?? r.connector_id.slice(0, 8)}</span>
          {r.scheduled_for && (
            <Tooltip title={t('apiGov.requests.scheduledFor', { when: fmtDate(r.scheduled_for) })}>
              <ClockCircleOutlined
                aria-label={t('apiGov.requests.scheduledForAria')}
                style={{ color: 'var(--af-color-primary, #6366f1)', fontSize: 12 }}
              />
            </Tooltip>
          )}
        </span>
      ),
    },
    { title: t('apiGov.requests.verb'), dataIndex: 'verb', width: 80, render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span> },
    {
      title: t('apiGov.requests.path'),
      dataIndex: 'request_path',
      ellipsis: true,
      render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
    },
    {
      title: t('apiGov.requests.status'),
      dataIndex: 'status',
      width: 130,
      render: (v: QueryStatus) => <StatusPill status={v} />,
    },
    {
      title: t('apiGov.requests.risk'),
      width: 120,
      render: (_v, r) =>
        r.ai_risk_level != null && r.ai_risk_score != null ? (
          <RiskPill level={r.ai_risk_level} score={r.ai_risk_score} />
        ) : (
          <span className="muted" style={{ fontSize: 11 }}>—</span>
        ),
    },
    {
      title: t('apiGov.requests.created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => <span className="muted" style={{ fontSize: 12 }}>{timeAgo(v)}</span>,
    },
    {
      title: t('apiGov.requests.actions'),
      key: 'actions',
      width: 160,
      render: (_v, r) => (
        <span style={{ display: 'flex', gap: 8 }} onClick={(e) => e.stopPropagation()}>
          <Button size="small" onClick={() => navigate(`/api-requests/${r.id}`)}>
            {t('apiGov.requests.view')}
          </Button>
          {r.status === 'APPROVED' && (
            <Button
              size="small"
              type="primary"
              loading={executeMutation.isPending && executeMutation.variables === r.id}
              onClick={() => executeMutation.mutate(r.id)}
            >
              {t('apiGov.requests.execute')}
            </Button>
          )}
          {(r.status === 'PENDING_AI' || r.status === 'PENDING_REVIEW') && (
            <Button
              size="small"
              danger
              loading={cancelMutation.isPending && cancelMutation.variables === r.id}
              onClick={() => cancelMutation.mutate(r.id)}
            >
              {t('apiGov.requests.cancel')}
            </Button>
          )}
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('apiGov.requests.title')} subtitle={t('apiGov.requests.subtitle')} />
      <div
        style={{
          padding: '12px 28px',
          background: 'var(--bg-elev)',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder={t('apiGov.requests.searchPlaceholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 240 }}
        />
        <Select
          value={status}
          onChange={(v) => { setStatus(v); setPage(0); }}
          options={[
            { value: 'all', label: t('apiGov.requests.filterAllStatuses') },
            ...enumOptions(STATUSES, queryStatusLabel, t),
          ]}
          style={{ width: 160 }}
        />
        <Select
          value={connector}
          onChange={(v) => { setConnector(v); setPage(0); }}
          options={[
            { value: 'all', label: t('apiGov.requests.filterAllConnectors') },
            ...(connectorsQuery.data?.content ?? []).map((c) => ({ value: c.id, label: c.name })),
          ]}
          style={{ width: 180 }}
        />
        <Select
          value={verb}
          onChange={(v) => { setVerb(v); setPage(0); }}
          options={[
            { value: 'all', label: t('apiGov.requests.filterAllVerbs') },
            ...VERBS.map((v) => ({ value: v, label: v })),
          ]}
          style={{ width: 120 }}
        />
        <Select
          value={risk}
          onChange={(v) => setRisk(v)}
          options={[
            { value: 'all', label: t('apiGov.requests.filterAllRisk') },
            ...enumOptions(RISKS, riskLevelLabel, t),
          ]}
          style={{ width: 130 }}
        />
        <DatePicker.RangePicker
          value={range as [Dayjs | null, Dayjs | null]}
          onChange={(v) => {
            setRange(v as [Dayjs | null, Dayjs | null] | null);
            setPage(0);
          }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('apiGov.requests.countLabel', {
            filtered: filtered.length,
            total: requestsQuery.data?.total_elements ?? 0,
          })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {requestsQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table<ApiRequest>
            rowKey="id"
            dataSource={filtered}
            columns={cols}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('apiGov.requests.empty') }}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: requestsQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            onRow={(record) => ({
              onClick: () => navigate(`/api-requests/${record.id}`),
              style: { cursor: 'pointer' },
            })}
          />
        )}
      </div>
    </div>
  );
}
