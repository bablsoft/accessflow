import { useMemo, useState } from 'react';
import { App, Button, Input, Modal, Select, Skeleton, Table, Tabs, Tag, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { RiskPill } from '@/components/common/RiskPill';
import {
  acknowledgeDeploymentRollback,
  approveDeployment,
  deploymentReviewKeys,
  deploymentRollbackReviewKeys,
  listDeploymentReviews,
  listDeploymentRollbackReviews,
  rejectDeployment,
} from '@/api/deploymentReviews';
import { deploymentKeys } from '@/api/deploymentRequests';
import { useAuthStore } from '@/store/authStore';
import {
  deploymentRollbackReviewStatusLabel,
  enumOptions,
  riskLevelLabel,
} from '@/utils/enumLabels';
import { deploymentRollbackReviewStatusColor } from '@/utils/statusColors';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  DeploymentReviewItem,
  DeploymentRollbackReview,
  DeploymentRollbackReviewStatus,
  RiskLevel,
} from '@/types/api';

const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const ROLLBACK_STATUSES: DeploymentRollbackReviewStatus[] = ['PENDING_REVIEW', 'REVIEWED'];

const PAGE_SIZE = 20;

function PendingDeploymentsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const [decisionFor, setDecisionFor] = useState<{ id: string; kind: 'approve' | 'reject' } | null>(
    null,
  );
  const [comment, setComment] = useState('');

  const [q, setQ] = useState('');
  const [pipeline, setPipeline] = useState<string | 'all'>('all');
  const [risk, setRisk] = useState<RiskLevel | 'all'>('all');
  const [page, setPage] = useState(0);

  const filters = useMemo(
    () => ({
      pipeline_id: pipeline === 'all' ? undefined : pipeline,
      page,
      size: PAGE_SIZE,
    }),
    [pipeline, page],
  );

  const queueQuery = useQuery({
    queryKey: deploymentReviewKeys.list(filters),
    queryFn: () => listDeploymentReviews(filters),
  });

  const rows = useMemo(() => queueQuery.data?.content ?? [], [queueQuery.data]);

  // A DEPLOYMENT_REVIEW-only user cannot list pipelines (needs MANAGE), so the
  // pipeline filter is built from the queue rows themselves.
  const pipelineOptions = useMemo(() => {
    const seen = new Map<string, string>();
    for (const row of rows) {
      if (!seen.has(row.pipeline_id)) {
        seen.set(row.pipeline_id, row.pipeline_name ?? row.pipeline_id);
      }
    }
    return [...seen.entries()].map(([value, label]) => ({ value, label }));
  }, [rows]);

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: deploymentReviewKeys.lists() });
    void queryClient.invalidateQueries({ queryKey: deploymentKeys.all });
  };

  const decideMutation = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'approve' | 'reject' }) =>
      kind === 'approve'
        ? approveDeployment(id, comment.trim() || undefined)
        : rejectDeployment(id, comment.trim() || undefined),
    onSuccess: (_data, vars) => {
      message.success(
        vars.kind === 'approve' ? t('deploygov.reviews.approved') : t('deploygov.reviews.rejected'),
      );
      setDecisionFor(null);
      setComment('');
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const filtered = useMemo(
    () =>
      rows.filter((r) => {
        if (risk !== 'all' && r.ai_risk_level !== risk) return false;
        if (q) {
          const n = q.toLowerCase();
          if (
            !(r.pipeline_name ?? '').toLowerCase().includes(n) &&
            !(r.environment_name ?? '').toLowerCase().includes(n) &&
            !r.version.toLowerCase().includes(n)
          ) {
            return false;
          }
        }
        return true;
      }),
    [rows, q, risk],
  );

  const columns: TableColumnsType<DeploymentReviewItem> = [
    {
      title: t('deploygov.deployments.pipeline'),
      dataIndex: 'pipeline_name',
      width: 170,
      render: (v: string | null, r) => v ?? r.pipeline_id,
    },
    { title: t('deploygov.deployments.environment'), dataIndex: 'environment_name', width: 130 },
    {
      title: t('deploygov.deployments.version'),
      dataIndex: 'version',
      width: 130,
      render: (v: string) => (
        <span className="mono" style={{ fontSize: 12 }}>
          {v}
        </span>
      ),
    },
    {
      title: t('deploygov.reviews.requiredApprovals'),
      width: 110,
      align: 'center' as const,
      render: (_v, r) => (
        <span className="mono" style={{ fontSize: 12 }}>
          {r.required_approvals}
        </span>
      ),
    },
    {
      title: t('deploygov.deployments.risk'),
      width: 120,
      render: (_v, r) =>
        r.ai_risk_level != null && r.ai_risk_score != null ? (
          <RiskPill level={r.ai_risk_level} score={r.ai_risk_score} />
        ) : (
          <span className="muted" style={{ fontSize: 11 }}>
            —
          </span>
        ),
    },
    {
      title: t('deploygov.reviews.scheduled'),
      dataIndex: 'scheduled_for',
      width: 140,
      render: (v: string | null) =>
        v ? <span style={{ fontSize: 12 }}>{fmtDate(v)}</span> : <span className="muted">—</span>,
    },
    {
      title: t('deploygov.deployments.created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => (
        <span className="muted" style={{ fontSize: 12 }}>
          {timeAgo(v)}
        </span>
      ),
    },
    {
      title: t('deploygov.reviews.actions'),
      key: 'actions',
      width: 180,
      render: (_v, row) => {
        const own = row.submitted_by_user_id === user?.id;
        const buttons = (
          <span style={{ display: 'flex', gap: 8 }}>
            <Button
              size="small"
              type="primary"
              disabled={own}
              onClick={(e) => {
                e.stopPropagation();
                setDecisionFor({ id: row.deployment_request_id, kind: 'approve' });
              }}
            >
              {t('deploygov.reviews.approve')}
            </Button>
            <Button
              size="small"
              danger
              disabled={own}
              onClick={(e) => {
                e.stopPropagation();
                setDecisionFor({ id: row.deployment_request_id, kind: 'reject' });
              }}
            >
              {t('deploygov.reviews.reject')}
            </Button>
          </span>
        );
        return own ? (
          <Tooltip title={t('deploygov.reviews.selfSubmissionHint')}>{buttons}</Tooltip>
        ) : (
          buttons
        );
      },
    },
  ];

  return (
    <>
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
          placeholder={t('deploygov.reviews.searchPlaceholder')}
          aria-label={t('deploygov.reviews.searchPlaceholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 240 }}
        />
        <Select
          value={pipeline}
          aria-label={t('deploygov.deployments.pipeline')}
          onChange={(v) => {
            setPipeline(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('deploygov.reviews.filterAllPipelines') },
            ...pipelineOptions,
          ]}
          style={{ width: 190 }}
        />
        <Select
          value={risk}
          aria-label={t('deploygov.deployments.risk')}
          onChange={(v) => setRisk(v)}
          options={[
            { value: 'all', label: t('deploygov.reviews.filterAllRisk') },
            ...enumOptions(RISKS, riskLevelLabel, t),
          ]}
          style={{ width: 140 }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('deploygov.reviews.countLabel', {
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
          <Table<DeploymentReviewItem>
            rowKey="deployment_request_id"
            dataSource={filtered}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('deploygov.reviews.empty') }}
            onRow={(row) => ({
              onClick: () => navigate(`/deployments/${row.deployment_request_id}`),
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
      {/* Dismissing clears the draft — a comment typed for one decision must never be carried
          into the next row's opposite decision. */}
      <Modal
        open={decisionFor !== null}
        title={
          decisionFor?.kind === 'approve'
            ? t('deploygov.reviews.approve')
            : t('deploygov.reviews.reject')
        }
        onCancel={() => {
          setDecisionFor(null);
          setComment('');
        }}
        okText={
          decisionFor?.kind === 'approve'
            ? t('deploygov.reviews.approve')
            : t('deploygov.reviews.reject')
        }
        confirmLoading={decideMutation.isPending}
        onOk={() => decisionFor && decideMutation.mutate(decisionFor)}
        destroyOnHidden
      >
        <Input.TextArea
          rows={3}
          maxLength={2000}
          placeholder={t('deploygov.reviews.comment')}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </Modal>
    </>
  );
}

function RollbackReviewsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const [status, setStatus] = useState<DeploymentRollbackReviewStatus | 'all'>('PENDING_REVIEW');
  const [page, setPage] = useState(0);
  const [ackFor, setAckFor] = useState<string | null>(null);
  const [comment, setComment] = useState('');

  const filters = useMemo(
    () => ({
      status: status === 'all' ? undefined : status,
      page,
      size: PAGE_SIZE,
    }),
    [status, page],
  );

  const listQuery = useQuery({
    queryKey: deploymentRollbackReviewKeys.list(filters),
    queryFn: () => listDeploymentRollbackReviews(filters),
  });

  const ackMutation = useMutation({
    mutationFn: (id: string) => acknowledgeDeploymentRollback(id, comment.trim() || undefined),
    onSuccess: () => {
      message.success(t('deploygov.reviews.acknowledged'));
      setAckFor(null);
      setComment('');
      void queryClient.invalidateQueries({ queryKey: deploymentRollbackReviewKeys.lists() });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const columns: TableColumnsType<DeploymentRollbackReview> = [
    {
      title: t('deploygov.reviews.rollbackStatus'),
      dataIndex: 'status',
      width: 140,
      render: (s: DeploymentRollbackReviewStatus) => {
        const color = deploymentRollbackReviewStatusColor(s);
        return (
          <Tag style={{ color: color.fg, background: color.bg, borderColor: color.border }}>
            {deploymentRollbackReviewStatusLabel(t, s)}
          </Tag>
        );
      },
    },
    {
      title: t('deploygov.reviews.rollbackDetail'),
      dataIndex: 'outcome_detail',
      ellipsis: true,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: t('deploygov.reviews.rollbackReviewedBy'),
      dataIndex: 'reviewed_by',
      width: 160,
      render: (v: string | null) =>
        v ? (
          <span className="mono" style={{ fontSize: 12 }}>
            {v}
          </span>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      title: t('deploygov.reviews.rollbackComment'),
      dataIndex: 'review_comment',
      ellipsis: true,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: t('deploygov.reviews.rollbackReviewedAt'),
      dataIndex: 'reviewed_at',
      width: 140,
      render: (v: string | null) => (v ? fmtDate(v) : '—'),
    },
    {
      title: t('deploygov.deployments.created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => (
        <span className="muted" style={{ fontSize: 12 }}>
          {timeAgo(v)}
        </span>
      ),
    },
    {
      title: t('deploygov.reviews.actions'),
      key: 'actions',
      width: 220,
      render: (_v, row) => {
        // The submitter can never acknowledge their own rollback (backend 409) — mirror the
        // pending tab and disable rather than surfacing the conflict as a toast.
        const own = row.submitted_by === user?.id;
        return (
          <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {row.status === 'PENDING_REVIEW' &&
              (own ? (
                <Tooltip title={t('deploygov.reviews.selfSubmissionHint')}>
                  <Button size="small" type="primary" disabled>
                    {t('deploygov.reviews.acknowledge')}
                  </Button>
                </Tooltip>
              ) : (
                <Button size="small" type="primary" onClick={() => setAckFor(row.id)}>
                  {t('deploygov.reviews.acknowledge')}
                </Button>
              ))}
            <Link to={`/deployments/${row.deployment_request_id}`}>
              {t('deploygov.reviews.viewDeployment')}
            </Link>
          </span>
        );
      },
    },
  ];

  return (
    <>
      <div
        style={{
          padding: '12px 28px',
          background: 'var(--bg-elev)',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          gap: 8,
          alignItems: 'center',
        }}
      >
        <Select
          value={status}
          aria-label={t('deploygov.reviews.rollbackStatus')}
          onChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('deploygov.reviews.filterAllRollbackStatuses') },
            ...enumOptions(ROLLBACK_STATUSES, deploymentRollbackReviewStatusLabel, t),
          ]}
          style={{ width: 170 }}
        />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {listQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table<DeploymentRollbackReview>
            rowKey="id"
            dataSource={listQuery.data?.content ?? []}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('deploygov.reviews.rollbackEmpty') }}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: listQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </div>
      <Modal
        open={ackFor !== null}
        title={t('deploygov.reviews.ackTitle')}
        onCancel={() => {
          setAckFor(null);
          setComment('');
        }}
        confirmLoading={ackMutation.isPending}
        okText={t('deploygov.reviews.acknowledge')}
        onOk={() => ackFor && ackMutation.mutate(ackFor)}
        destroyOnHidden
      >
        <Input.TextArea
          rows={3}
          maxLength={2000}
          placeholder={t('deploygov.reviews.ackComment')}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </Modal>
    </>
  );
}

export default function DeploymentReviewQueuePage() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') === 'rollbacks' ? 'rollbacks' : 'pending';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('deploygov.reviews.title')} subtitle={t('deploygov.reviews.subtitle')} />
      <Tabs
        activeKey={tab}
        onChange={(key) => {
          setSearchParams(key === 'rollbacks' ? { tab: 'rollbacks' } : {}, { replace: true });
        }}
        style={{ padding: '0 28px' }}
        items={[
          { key: 'pending', label: t('deploygov.reviews.tabPending') },
          { key: 'rollbacks', label: t('deploygov.reviews.tabRollbacks') },
        ]}
      />
      {tab === 'pending' ? <PendingDeploymentsTab /> : <RollbackReviewsTab />}
    </div>
  );
}
