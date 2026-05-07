import { useMemo, useState } from 'react';
import { Button, DatePicker, Input, Select, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import type { Dayjs } from 'dayjs';
import { DownloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { Avatar } from '@/components/common/Avatar';
import { listQueries, queryKeys } from '@/api/queries';
import { timeAgo } from '@/utils/dateFormat';
import type {
  QueryListItem,
  QueryStatus,
  QueryType,
  RiskLevel,
} from '@/types/api';

const STATUSES: QueryStatus[] = [
  'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'FAILED', 'CANCELLED',
];
const TYPES: QueryType[] = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'DDL'];
const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

const PAGE_SIZE = 20;

export function QueryListPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [q, setQ] = useState('');
  const [status, setStatus] = useState<QueryStatus | 'all'>('all');
  const [type, setType] = useState<QueryType | 'all'>('all');
  const [risk, setRisk] = useState<RiskLevel | 'all'>('all');
  const [datasource, setDatasource] = useState<string | 'all'>('all');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [page, setPage] = useState(0);

  const filters = useMemo(
    () => ({
      status: status === 'all' ? undefined : status,
      query_type: type === 'all' ? undefined : type,
      datasource_id: datasource === 'all' ? undefined : datasource,
      from: range?.[0] ? range[0].toISOString() : undefined,
      to: range?.[1] ? range[1].endOf('day').toISOString() : undefined,
      page,
      size: PAGE_SIZE,
    }),
    [status, type, datasource, range, page],
  );

  const { data, isLoading } = useQuery({
    queryKey: queryKeys.list(filters),
    queryFn: () => listQueries(filters),
  });

  const rows = useMemo(() => data?.content ?? [], [data]);

  const dsOpts = useMemo(() => {
    const m = new Map<string, string>();
    rows.forEach((qr) => m.set(qr.datasource.id, qr.datasource.name));
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [rows]);

  // Free-text + risk filter happen client-side over the current page (server has no full-text
  // filter; risk lives on the embedded ai_analysis snapshot, which is also not server-filterable
  // yet).
  const filtered = useMemo(
    () =>
      rows.filter((qr) => {
        if (risk !== 'all' && qr.risk_level !== risk) return false;
        if (q) {
          const n = q.toLowerCase();
          if (
            !qr.id.toLowerCase().includes(n) &&
            !qr.submitted_by.display_name.toLowerCase().includes(n) &&
            !qr.submitted_by.email.toLowerCase().includes(n) &&
            !qr.datasource.name.toLowerCase().includes(n)
          ) {
            return false;
          }
        }
        return true;
      }),
    [rows, q, risk],
  );

  const cols: TableColumnsType<QueryListItem> = [
    {
      title: t('queries.list.col_id'),
      dataIndex: 'id',
      width: 110,
      render: (v: string) => <span className="mono muted" style={{ fontSize: 12 }}>{v.slice(0, 8)}</span>,
    },
    {
      title: t('queries.list.col_type'),
      dataIndex: 'query_type',
      width: 80,
      render: (v: QueryType) => <QueryTypePill type={v} />,
    },
    {
      title: t('queries.list.col_status'),
      dataIndex: 'status',
      width: 130,
      render: (v: QueryStatus) => <StatusPill status={v} />,
    },
    {
      title: t('queries.list.col_risk'),
      width: 130,
      render: (_v, r) =>
        r.risk_level != null && r.risk_score != null ? (
          <RiskPill level={r.risk_level} score={r.risk_score} />
        ) : (
          <span className="muted" style={{ fontSize: 11 }}>—</span>
        ),
    },
    {
      title: t('queries.list.col_datasource'),
      width: 200,
      render: (_v, r) => (
        <span className="mono" style={{ fontSize: 12 }}>{r.datasource.name}</span>
      ),
    },
    {
      title: t('queries.list.col_submitter'),
      width: 180,
      render: (_v, r) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Avatar name={r.submitted_by.display_name} size={20} />
          <span style={{ fontSize: 12 }}>{r.submitted_by.display_name}</span>
        </div>
      ),
    },
    {
      title: t('queries.list.col_created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => <span className="muted" style={{ fontSize: 12 }}>{timeAgo(v)}</span>,
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('queries.list.title')}
        subtitle={t('queries.list.subtitle')}
        actions={<Button icon={<DownloadOutlined />}>{t('common.export_csv')}</Button>}
      />
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
          placeholder={t('queries.list.search_placeholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 260 }}
        />
        <Select
          value={status}
          onChange={(v) => { setStatus(v); setPage(0); }}
          options={[
            { value: 'all', label: t('queries.list.filter_all_statuses') },
            ...STATUSES.map((s) => ({ value: s, label: s.replace('_', ' ') })),
          ]}
          style={{ width: 160 }}
        />
        <Select
          value={type}
          onChange={(v) => { setType(v); setPage(0); }}
          options={[
            { value: 'all', label: t('queries.list.filter_all_types') },
            ...TYPES.map((tp) => ({ value: tp, label: tp })),
          ]}
          style={{ width: 130 }}
        />
        <Select
          value={risk}
          onChange={(v) => setRisk(v)}
          options={[
            { value: 'all', label: t('queries.list.filter_all_risk') },
            ...RISKS.map((r) => ({ value: r, label: r })),
          ]}
          style={{ width: 130 }}
        />
        <Select
          value={datasource}
          onChange={(v) => { setDatasource(v); setPage(0); }}
          options={[
            { value: 'all', label: t('queries.list.filter_all_datasources') },
            ...dsOpts.map(([id, name]) => ({ value: id, label: name })),
          ]}
          style={{ width: 200 }}
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
          {t('queries.list.count_label', {
            filtered: filtered.length,
            total: data?.total_elements ?? 0,
          })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table
            rowKey="id"
            dataSource={filtered}
            columns={cols}
            size="middle"
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
            onRow={(record) => ({
              onClick: () => navigate(`/queries/${record.id}`),
              style: { cursor: 'pointer' },
            })}
          />
        )}
      </div>
    </div>
  );
}
