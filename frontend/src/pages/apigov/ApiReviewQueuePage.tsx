import { useMemo, useState } from 'react';
import { App, Button, Input, Modal, Select, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { RiskPill } from '@/components/common/RiskPill';
import {
  apiRequestKeys,
  approveApiReview,
  listPendingApiReviews,
  rejectApiReview,
} from '@/api/apiRequests';
import { apiConnectorKeys, listApiConnectors } from '@/api/apiConnectors';
import { enumOptions, riskLevelLabel } from '@/utils/enumLabels';
import { timeAgo } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { PendingApiReview, RiskLevel } from '@/types/api';

const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const VERBS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

const PAGE_SIZE = 20;

export default function ApiReviewQueuePage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [decisionFor, setDecisionFor] = useState<{ id: string; kind: 'approve' | 'reject' } | null>(
    null,
  );
  const [comment, setComment] = useState('');

  const [q, setQ] = useState('');
  const [connector, setConnector] = useState<string | 'all'>('all');
  const [verb, setVerb] = useState<string | 'all'>('all');
  const [risk, setRisk] = useState<RiskLevel | 'all'>('all');
  const [page, setPage] = useState(0);

  const filters = useMemo(
    () => ({
      connector_id: connector === 'all' ? undefined : connector,
      verb: verb === 'all' ? undefined : verb,
      page,
      size: PAGE_SIZE,
    }),
    [connector, verb, page],
  );

  const queueQuery = useQuery({
    queryKey: apiRequestKeys.reviewQueue(filters),
    queryFn: () => listPendingApiReviews(filters),
  });

  const connectorsQuery = useQuery({
    queryKey: apiConnectorKeys.list({ size: 100 }),
    queryFn: () => listApiConnectors({ size: 100 }),
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ['api-reviews', 'queue'] });

  const decideMutation = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'approve' | 'reject' }) =>
      kind === 'approve' ? approveApiReview(id, comment) : rejectApiReview(id, comment),
    onSuccess: (_data, vars) => {
      message.success(vars.kind === 'approve' ? t('apiGov.reviews.approved') : t('apiGov.reviews.rejected'));
      setDecisionFor(null);
      setComment('');
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const rows = useMemo(() => queueQuery.data?.content ?? [], [queueQuery.data]);

  const filtered = useMemo(
    () =>
      rows.filter((r) => {
        if (risk !== 'all' && r.ai_risk_level !== risk) return false;
        if (q) {
          const n = q.toLowerCase();
          if (
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

  const columns: TableColumnsType<PendingApiReview> = [
    { title: t('apiGov.requests.connector'), dataIndex: 'connector_name', width: 160 },
    { title: t('apiGov.requests.verb'), dataIndex: 'verb', width: 80, render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span> },
    {
      title: t('apiGov.requests.path'),
      dataIndex: 'request_path',
      ellipsis: true,
      render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
    },
    {
      title: t('apiGov.reviews.risk'),
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
      title: t('apiGov.reviews.actions'),
      key: 'actions',
      width: 180,
      render: (_v, row) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            type="primary"
            onClick={(e) => {
              e.stopPropagation();
              setDecisionFor({ id: row.api_request_id, kind: 'approve' });
            }}
          >
            {t('apiGov.reviews.approve')}
          </Button>
          <Button
            size="small"
            danger
            onClick={(e) => {
              e.stopPropagation();
              setDecisionFor({ id: row.api_request_id, kind: 'reject' });
            }}
          >
            {t('apiGov.reviews.reject')}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('apiGov.reviews.title')} subtitle={t('apiGov.reviews.subtitle')} />
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
          placeholder={t('apiGov.reviews.searchPlaceholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 240 }}
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
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('apiGov.requests.countLabel', {
            filtered: filtered.length,
            total: queueQuery.data?.total_elements ?? 0,
          })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {queueQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table<PendingApiReview>
            rowKey="api_request_id"
            dataSource={filtered}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('apiGov.reviews.empty') }}
            onRow={(row) => ({
              onClick: () => navigate(`/api-requests/${row.api_request_id}`),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: queueQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </div>
      <Modal
        open={decisionFor !== null}
        title={decisionFor?.kind === 'approve' ? t('apiGov.reviews.approve') : t('apiGov.reviews.reject')}
        onCancel={() => setDecisionFor(null)}
        confirmLoading={decideMutation.isPending}
        onOk={() => decisionFor && decideMutation.mutate(decisionFor)}
      >
        <Input.TextArea
          rows={3}
          placeholder={t('apiGov.reviews.comment')}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </Modal>
    </div>
  );
}
