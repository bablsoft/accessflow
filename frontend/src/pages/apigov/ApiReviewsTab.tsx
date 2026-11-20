import { useMemo, useState } from 'react';
import { App, Button, Input, Modal, Select, Skeleton, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { DataTable, FilterBar, PageBody, StatusTag } from '@/components/layout';
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
import type { PendingApiReview, PendingApiReviewPage, RiskLevel } from '@/types/api';

const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const VERBS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

const PAGE_SIZE = 20;

interface DecisionVars {
  id: string;
  kind: 'approve' | 'reject';
  comment: string;
  /** The row to select once this one leaves the queue. */
  nextId: string | null;
}

/**
 * The API requests tab of the unified review hub (#772) — formerly the `/api-reviews` page.
 */
export function ApiReviewsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [decisionFor, setDecisionFor] = useState<{ id: string; kind: 'approve' | 'reject' } | null>(
    null,
  );
  const [comment, setComment] = useState('');
  const [activeId, setActiveId] = useState<string | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  // Wide enough for the list and a preview side by side; below it a row click opens the detail page.
  const split = useMediaQuery('(min-width: 1440px)');

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
    queryClient.invalidateQueries({ queryKey: apiRequestKeys.reviewQueues() });

  const { remove, restore } = useOptimisticRemoval<PendingApiReview, PendingApiReviewPage>(
    apiRequestKeys.reviewQueue(filters),
    (row) => row.api_request_id,
  );

  // The decided row leaves the queue at once and the selection moves to its neighbour; if the
  // server refuses, the row comes back and the error explains why.
  const decideMutation = useMutation({
    mutationFn: ({ id, kind, comment: text }: DecisionVars) =>
      kind === 'approve' ? approveApiReview(id, text) : rejectApiReview(id, text),
    onMutate: async ({ id, nextId }) => {
      setDecisionFor(null);
      setComment('');
      setActiveId(nextId);
      return { ...(await remove(id)), id };
    },
    onSuccess: (_data, vars) => {
      message.success(vars.kind === 'approve' ? t('apiGov.reviews.approved') : t('apiGov.reviews.rejected'));
    },
    onError: (err, _vars, context) => {
      restore(context);
      if (context) setActiveId(context.id);
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error')));
    },
    onSettled: () => invalidate(),
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

  const active = filtered.find((r) => r.api_request_id === activeId) ?? filtered[0] ?? null;
  const previewContent = active && {
    id: active.api_request_id,
    heading: active.api_request_id.slice(0, 8),
    badges:
      active.ai_risk_level && active.ai_risk_score != null ? (
        <RiskPill level={active.ai_risk_level} score={active.ai_risk_score} size="sm" />
      ) : null,
    meta: [
      { label: t('apiGov.requests.connector'), value: active.connector_name ?? '—' },
      { label: t('apiGov.requests.verb'), value: <span className="mono">{active.verb}</span> },
      { label: t('apiGov.requests.path'), value: <span className="mono">{active.request_path}</span> },
      { label: t('apiGov.reviews.overrides'), value: active.variable_override_count },
      { label: t('reviews.col_created'), value: timeAgo(active.created_at) },
    ],
    sections: [
      { label: t('reviews.preview.justification'), content: active.justification ?? '' },
      { label: t('reviews.preview.ai_summary'), content: active.ai_summary ?? '' },
    ],
    detailsHref: `/api-requests/${active.api_request_id}`,
  };
  useTriageKeys({
    ids: filtered.map((r) => r.api_request_id),
    activeId: active?.api_request_id ?? null,
    onActiveChange: setActiveId,
    onOpen: (id) => navigate(`/api-requests/${id}`),
    onApprove: (id) => setDecisionFor({ id, kind: 'approve' }),
    onReject: (id) => setDecisionFor({ id, kind: 'reject' }),
    onHelp: () => setHelpOpen((open) => !open),
  });

  const columns: TableColumnsType<PendingApiReview> = [
    { title: t('apiGov.requests.connector'), dataIndex: 'connector_name', width: 160 },
    { title: t('apiGov.requests.verb'), dataIndex: 'verb', width: 80, render: (v: string) => <span className="mono u-text-xs">{v}</span> },
    {
      title: t('apiGov.requests.path'),
      dataIndex: 'request_path',
      ellipsis: true,
      render: (v: string) => <span className="mono u-text-xs">{v}</span>,
    },
    {
      title: t('apiGov.reviews.overrides'),
      dataIndex: 'variable_override_count',
      width: 110,
      align: 'center' as const,
      render: (count: number) =>
        count > 0 ? (
          <Tooltip title={t('apiGov.reviews.overridesHint')}>
            <StatusTag tone="info">{count}</StatusTag>
          </Tooltip>
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      title: t('apiGov.reviews.risk'),
      width: 120,
      render: (_v, r) =>
        r.ai_risk_level != null && r.ai_risk_score != null ? (
          <RiskPill level={r.ai_risk_level} score={r.ai_risk_score} />
        ) : (
          <span className="muted u-text-2xs">—</span>
        ),
    },
    {
      title: t('apiGov.requests.created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => <span className="muted u-text-xs">{timeAgo(v)}</span>,
    },
    {
      title: t('apiGov.reviews.actions'),
      key: 'actions',
      width: 180,
      render: (_v, row) => (
        <span className="u-flex u-gap-2">
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
    <div className="u-flex u-col u-flex-1 u-min-h-0">
      <FilterBar>
        <Input
          prefix={<SearchOutlined className="u-fg-faint" />}
          placeholder={t('apiGov.reviews.searchPlaceholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="u-w-lg"
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
          className="u-w-xs"
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
        <div className="u-flex-1" />
        <TriageShortcuts open={helpOpen} onOpenChange={setHelpOpen} />
        <span className="mono muted u-text-2xs">
          {t('apiGov.requests.countLabel', {
            filtered: filtered.length,
            total: queueQuery.data?.total_elements ?? 0,
          })}
        </span>
      </FilterBar>
      <div className={`af-triage${filtered.length > 0 ? ' af-triage--split' : ''}`}>
      <PageBody flush>
        {queueQuery.isLoading ? (
          <div className="u-p-4">
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <DataTable<PendingApiReview>
            rowKey="api_request_id"
            rowClassName={(row) => (row.api_request_id === active?.api_request_id ? 'af-triage-row-active' : '')}
            dataSource={filtered}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            emptyTitle={t('apiGov.reviews.empty')}
            onRow={(row) => ({
              onClick: () =>
                split ? setActiveId(row.api_request_id) : navigate(`/api-requests/${row.api_request_id}`),
              onDoubleClick: () => navigate(`/api-requests/${row.api_request_id}`),
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
      <Modal
        open={decisionFor !== null}
        title={decisionFor?.kind === 'approve' ? t('apiGov.reviews.approve') : t('apiGov.reviews.reject')}
        onCancel={() => setDecisionFor(null)}
        confirmLoading={decideMutation.isPending}
        onOk={() =>
          decisionFor &&
          decideMutation.mutate({
            ...decisionFor,
            comment,
            nextId: nextActiveId(filtered.map((r) => r.api_request_id), decisionFor.id),
          })
        }
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
