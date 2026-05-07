import { useMemo, useState } from 'react';
import { Button, DatePicker, Input, Select, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { DownloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { Avatar } from '@/components/common/Avatar';
import { useQueriesStore } from '@/store/queriesStore';
import { timeAgo } from '@/utils/dateFormat';
import type { QueryRequest, QueryStatus, QueryType, RiskLevel } from '@/types/api';

const STATUSES: QueryStatus[] = [
  'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'FAILED', 'CANCELLED',
];
const TYPES: QueryType[] = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'DDL'];
const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export function QueryListPage() {
  const { t } = useTranslation();
  const queries = useQueriesStore((s) => s.queries);
  const navigate = useNavigate();

  const [q, setQ] = useState('');
  const [status, setStatus] = useState<QueryStatus | 'all'>('all');
  const [type, setType] = useState<QueryType | 'all'>('all');
  const [risk, setRisk] = useState<RiskLevel | 'all'>('all');
  const [submitter, setSubmitter] = useState<string | 'all'>('all');
  const [datasource, setDatasource] = useState<string | 'all'>('all');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);

  const submitters = useMemo(() => {
    const m = new Map<string, string>();
    queries.forEach((qr) => m.set(qr.submitted_by, qr.submitter_name));
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [queries]);
  const dsOpts = useMemo(() => {
    const m = new Map<string, string>();
    queries.forEach((qr) => m.set(qr.datasource_id, qr.datasource_name));
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [queries]);

  const filtered = useMemo(
    () =>
      queries.filter((qr) => {
        if (status !== 'all' && qr.status !== status) return false;
        if (type !== 'all' && qr.query_type !== type) return false;
        if (risk !== 'all' && qr.risk_level !== risk) return false;
        if (submitter !== 'all' && qr.submitted_by !== submitter) return false;
        if (datasource !== 'all' && qr.datasource_id !== datasource) return false;
        if (range?.[0] && dayjs(qr.created_at).isBefore(range[0])) return false;
        if (range?.[1] && dayjs(qr.created_at).isAfter(range[1].endOf('day'))) return false;
        if (q) {
          const n = q.toLowerCase();
          if (
            !qr.sql.toLowerCase().includes(n) &&
            !qr.submitter_name.toLowerCase().includes(n) &&
            !qr.id.toLowerCase().includes(n)
          )
            return false;
        }
        return true;
      }),
    [queries, q, status, type, risk, submitter, datasource, range],
  );

  const cols: TableColumnsType<QueryRequest> = [
    { title: t('queries.list.col_id'), dataIndex: 'id', width: 110, render: (v: string) => <span className="mono muted" style={{ fontSize: 12 }}>{v}</span> },
    {
      title: t('queries.list.col_sql'),
      dataIndex: 'sql',
      ellipsis: true,
      render: (v: string) => (
        <div
          className="mono"
          style={{
            fontSize: 12,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            maxWidth: 380,
          }}
        >
          {v.replace(/\s+/g, ' ').slice(0, 100)}
        </div>
      ),
    },
    { title: t('queries.list.col_type'), dataIndex: 'query_type', width: 80, render: (v: QueryType) => <QueryTypePill type={v} /> },
    { title: t('queries.list.col_status'), dataIndex: 'status', width: 130, render: (v: QueryStatus) => <StatusPill status={v} /> },
    { title: t('queries.list.col_risk'), dataIndex: 'risk_level', width: 130, render: (_v: RiskLevel, r) => <RiskPill level={r.risk_level} score={r.risk_score} /> },
    { title: t('queries.list.col_datasource'), dataIndex: 'datasource_name', width: 200, render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span> },
    {
      title: t('queries.list.col_submitter'),
      dataIndex: 'submitter_name',
      width: 180,
      render: (v: string) => (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Avatar name={v} size={20} />
          <span style={{ fontSize: 12 }}>{v}</span>
        </div>
      ),
    },
    { title: t('queries.list.col_created'), dataIndex: 'created_at', width: 110, render: (v: string) => <span className="muted" style={{ fontSize: 12 }}>{timeAgo(v)}</span> },
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
          onChange={(v) => setStatus(v)}
          options={[
            { value: 'all', label: t('queries.list.filter_all_statuses') },
            ...STATUSES.map((s) => ({ value: s, label: s.replace('_', ' ') })),
          ]}
          style={{ width: 160 }}
        />
        <Select
          value={type}
          onChange={(v) => setType(v)}
          options={[{ value: 'all', label: t('queries.list.filter_all_types') }, ...TYPES.map((tp) => ({ value: tp, label: tp }))]}
          style={{ width: 130 }}
        />
        <Select
          value={risk}
          onChange={(v) => setRisk(v)}
          options={[{ value: 'all', label: t('queries.list.filter_all_risk') }, ...RISKS.map((r) => ({ value: r, label: r }))]}
          style={{ width: 130 }}
        />
        <Select
          value={submitter}
          onChange={(v) => setSubmitter(v)}
          options={[
            { value: 'all', label: t('queries.list.filter_all_submitters') },
            ...submitters.map(([id, name]) => ({ value: id, label: name })),
          ]}
          style={{ width: 180 }}
        />
        <Select
          value={datasource}
          onChange={(v) => setDatasource(v)}
          options={[
            { value: 'all', label: t('queries.list.filter_all_datasources') },
            ...dsOpts.map(([id, name]) => ({ value: id, label: name })),
          ]}
          style={{ width: 200 }}
        />
        <DatePicker.RangePicker
          value={range as [Dayjs | null, Dayjs | null]}
          onChange={(v) => setRange(v as [Dayjs | null, Dayjs | null] | null)}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('queries.list.count_label', { filtered: filtered.length, total: queries.length })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        <Table
          rowKey="id"
          dataSource={filtered}
          columns={cols}
          size="middle"
          pagination={{ pageSize: 15, showSizeChanger: false }}
          onRow={(record) => ({
            onClick: () => navigate(`/queries/${record.id}`),
            style: { cursor: 'pointer' },
          })}
        />
      </div>
    </div>
  );
}
