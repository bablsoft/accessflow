import { useMemo, useState } from 'react';
import { App, Button, Input, Modal, Select, Skeleton, Tag, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { DataTable, FilterBar, PageBody } from '@/components/layout';
import {
  ReviewPreview,
  TriageShortcuts,
  nextActiveId,
  useOptimisticRemoval,
  useTriageKeys,
} from '@/components/review/triage';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import '@/components/review/triage/triage.css';
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
  DeploymentReviewPage,
  DeploymentRollbackReview,
  DeploymentRollbackReviewStatus,
  RiskLevel,
} from '@/types/api';

const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const ROLLBACK_STATUSES: DeploymentRollbackReviewStatus[] = ['PENDING_REVIEW', 'REVIEWED'];

const PAGE_SIZE = 20;

interface DecisionVars {
  id: string;
  kind: 'approve' | 'reject';
  comment: string;
  /** The row to select once this one leaves the queue. */
  nextId: string | null;
}

/**
 * The Deployments tab of the unified review hub (#772) — the pending-deployments queue that
 * used to be the default tab of `/deployment-reviews`.
 */
export function PendingDeploymentsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const [decisionFor, setDecisionFor] = useState<{ id: string; kind: 'approve' | 'reject' } | null>(
    null,
  );
  const [comment, setComment] = useState('');
  const [activeId, setActiveId] = useState<string | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  // Wide enough for the list and a preview side by side; below it a row click opens the detail page.
  const split = useMediaQuery('(min-width: 1440px)');

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

  const { remove, restore } = useOptimisticRemoval<DeploymentReviewItem, DeploymentReviewPage>(
    deploymentReviewKeys.list(filters),
    (row) => row.deployment_request_id,
  );

  // The decided row leaves the queue at once and the selection moves to its neighbour; if the
  // server refuses, the row comes back and the error explains why.
  const decideMutation = useMutation({
    mutationFn: ({ id, kind, comment: text }: DecisionVars) =>
      kind === 'approve'
        ? approveDeployment(id, text.trim() || undefined)
        : rejectDeployment(id, text.trim() || undefined),
    onMutate: async ({ id, nextId }) => {
      setDecisionFor(null);
      setComment('');
      setActiveId(nextId);
      return { ...(await remove(id)), id };
    },
    onSuccess: (_data, vars) => {
      message.success(
        vars.kind === 'approve' ? t('deploygov.reviews.approved') : t('deploygov.reviews.rejected'),
      );
    },
    onError: (err, _vars, context) => {
      restore(context);
      if (context) setActiveId(context.id);
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error')));
    },
    onSettled: () => invalidate(),
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

  const active = filtered.find((r) => r.deployment_request_id === activeId) ?? filtered[0] ?? null;
  // A reviewer can never decide their own deployment; the row buttons are disabled for it, and the
  // keyboard and the preview follow the same rule.
  const isOwn = (id: string) =>
    filtered.some((r) => r.deployment_request_id === id && r.submitted_by_user_id === user?.id);
  const previewContent = active && {
    id: active.deployment_request_id,
    heading: active.version,
    badges:
      active.ai_risk_level && active.ai_risk_score != null ? (
        <RiskPill level={active.ai_risk_level} score={active.ai_risk_score} size="sm" />
      ) : null,
    meta: [
      { label: t('deploygov.deployments.pipeline'), value: active.pipeline_name ?? active.pipeline_id },
      { label: t('deploygov.deployments.environment'), value: active.environment_name ?? active.environment_id },
      ...(active.commit_sha
        ? [{ label: t('deploygov.detail.commit'), value: <span className="mono">{active.commit_sha.slice(0, 12)}</span> }]
        : []),
      ...(active.scheduled_for
        ? [{ label: t('deploygov.deployments.scheduled'), value: fmtDate(active.scheduled_for) }]
        : []),
      { label: t('reviews.col_created'), value: timeAgo(active.created_at) },
    ],
    sections: [
      { label: t('reviews.preview.justification'), content: active.justification ?? '' },
      { label: t('reviews.preview.ai_summary'), content: active.ai_summary ?? '' },
    ],
    detailsHref: `/deployments/${active.deployment_request_id}`,
    decidable: active.submitted_by_user_id !== user?.id,
  };
  useTriageKeys({
    ids: filtered.map((r) => r.deployment_request_id),
    activeId: active?.deployment_request_id ?? null,
    onActiveChange: setActiveId,
    onOpen: (id) => navigate(`/deployments/${id}`),
    onApprove: (id) => { if (!isOwn(id)) setDecisionFor({ id, kind: 'approve' }); },
    onReject: (id) => { if (!isOwn(id)) setDecisionFor({ id, kind: 'reject' }); },
    onHelp: () => setHelpOpen((open) => !open),
  });

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
      <FilterBar>
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
        <TriageShortcuts open={helpOpen} onOpenChange={setHelpOpen} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('deploygov.reviews.countLabel', {
            filtered: filtered.length,
            total: queueQuery.data?.total_elements ?? 0,
          })}
        </span>
      </FilterBar>
      <div className={`af-triage${filtered.length > 0 ? ' af-triage--split' : ''}`}>
      <PageBody flush>
        {queueQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <DataTable<DeploymentReviewItem>
            rowKey="deployment_request_id"
            rowClassName={(row) => (row.deployment_request_id === active?.deployment_request_id ? 'af-triage-row-active' : '')}
            dataSource={filtered}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            emptyTitle={t('deploygov.reviews.empty')}
            onRow={(row) => ({
              onClick: () =>
                split ? setActiveId(row.deployment_request_id) : navigate(`/deployments/${row.deployment_request_id}`),
              onDoubleClick: () => navigate(`/deployments/${row.deployment_request_id}`),
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
      </PageBody>
      {split && filtered.length > 0 && (
        <aside className="af-triage-preview">
          <ReviewPreview
            content={previewContent}
            onApprove={(id) => setDecisionFor({ id, kind: 'approve' })}
            onReject={(id) => setDecisionFor({ id, kind: 'reject' })}
          />
        </aside>
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
        onOk={() =>
          decisionFor &&
          decideMutation.mutate({
            ...decisionFor,
            comment,
            nextId: nextActiveId(filtered.map((r) => r.deployment_request_id), decisionFor.id),
          })
        }
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

/**
 * The Rollbacks tab of the unified review hub (#772) — the mandatory post-rollback
 * acknowledgements, formerly `/deployment-reviews?tab=rollbacks`.
 */
export function RollbackReviewsTab() {
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
      <FilterBar>
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
      </FilterBar>
      <PageBody flush>
        {listQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <DataTable<DeploymentRollbackReview>
            rowKey="id"
            dataSource={listQuery.data?.content ?? []}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            emptyTitle={t('deploygov.reviews.rollbackEmpty')}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: listQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </PageBody>
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
