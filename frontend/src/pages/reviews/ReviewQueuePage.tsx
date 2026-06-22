import { App, Button, Skeleton, Space, Table, Tabs, Tag, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { RiskPill } from '@/components/common/RiskPill';
import { Avatar } from '@/components/common/Avatar';
import { RejectModal } from '@/components/review/RejectModal';
import { BulkDecisionModal } from '@/components/review/BulkDecisionModal';
import { PushApprovalsToggle } from '@/components/review/PushApprovalsToggle';
import { useAuthStore } from '@/store/authStore';
import { timeAgo } from '@/utils/dateFormat';
import { queryKeys } from '@/api/queries';
import {
  approveQuery,
  bulkDecideReviews,
  listPendingReviews,
  rejectQuery,
  reviewKeys,
  type PendingReviewsFilters,
} from '@/api/reviews';
import { reviewErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  BulkReviewRowStatus,
  PendingReviewItem,
  ReviewDecisionType,
} from '@/types/api';

type BulkAction = ReviewDecisionType | null;

export function ReviewQueuePage() {
  const { t } = useTranslation();
  const user = useAuthStore((s) => s.user);
  const [tab, setTab] = useState('mine');
  const [rejectTargetId, setRejectTargetId] = useState<string | null>(null);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [bulkAction, setBulkAction] = useState<BulkAction>(null);
  const [rowStatuses, setRowStatuses] = useState<Record<string, BulkReviewRowStatus>>({});
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const filters: PendingReviewsFilters = { size: 50 };
  const { data, isLoading, refetch } = useQuery({
    queryKey: reviewKeys.pendingFor(filters),
    queryFn: () => listPendingReviews(filters),
  });

  const all = data?.content ?? [];
  // Defense-in-depth: backend already excludes the caller's own queries (self-approval is 403),
  // but we filter client-side too so a stale cache never offers an action that will fail.
  const reviewable = all.filter((q) => q.submitted_by.id !== user?.id);
  const list = tab === 'mine' ? reviewable.slice(0, 8) : reviewable;

  const invalidateAfterDecision = (queryId: string) => {
    void queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    void queryClient.invalidateQueries({ queryKey: queryKeys.detail(queryId) });
    void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
  };

  const approve = useMutation({
    mutationFn: (id: string) => approveQuery(id),
    onSuccess: (_, id) => {
      invalidateAfterDecision(id);
      message.success(t('reviews.on_approve'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });
  const reject = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment: string }) =>
      rejectQuery(id, comment),
    onSuccess: (_, vars) => {
      invalidateAfterDecision(vars.id);
      setRejectTargetId(null);
      message.error(t('reviews.on_reject'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const bulk = useMutation({
    mutationFn: ({ decision, comment }: { decision: ReviewDecisionType; comment: string }) =>
      bulkDecideReviews({ queryIds: selectedRowKeys, decision, comment: comment || undefined }),
    onSuccess: (response) => {
      const failures: Record<string, BulkReviewRowStatus> = {};
      let success = 0;
      for (const row of response.results) {
        if (row.status === 'SUCCESS') {
          success += 1;
        } else {
          failures[row.query_request_id] = row.status;
        }
      }
      const failure = Object.keys(failures).length;
      // Keep failed rows selected so the user can retry; drop the successes.
      setSelectedRowKeys((prev) => prev.filter((id) => failures[id] !== undefined));
      setRowStatuses((prev) => ({ ...prev, ...failures }));
      void queryClient.invalidateQueries({ queryKey: reviewKeys.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      if (failure === 0) {
        message.success(
          t('reviews.bulk.toast_summary', { count: success, success, failure }),
        );
      } else {
        message.warning(t('reviews.bulk.toast_partial', { success, failure }));
      }
      setBulkAction(null);
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const rowStatusTag = (status: BulkReviewRowStatus) => {
    const labelKey =
      status === 'FORBIDDEN'
        ? 'reviews.bulk.row_status_forbidden'
        : status === 'INVALID_STATE'
          ? 'reviews.bulk.row_status_invalid_state'
          : 'reviews.bulk.row_status_not_found';
    const color = status === 'INVALID_STATE' ? 'warning' : 'error';
    return <Tag color={color}>{t(labelKey)}</Tag>;
  };

  const columns: TableColumnsType<PendingReviewItem> = useMemo(
    () => [
      {
        title: t('reviews.col_id'),
        dataIndex: 'id',
        key: 'id',
        width: 220,
        render: (id: string) => (
          <Tooltip title={id} placement="topLeft">
            <div className="mono" style={{ fontSize: 11, lineHeight: 1.4 }}>
              <div style={{ fontWeight: 500 }}>{id.slice(0, 8)}</div>
              <div className="muted" style={{ fontSize: 10 }}>{id}</div>
            </div>
          </Tooltip>
        ),
      },
      {
        title: t('reviews.col_type'),
        dataIndex: 'query_type',
        key: 'query_type',
        width: 110,
        render: (_: unknown, item: PendingReviewItem) => (
          <QueryTypePill type={item.query_type} size="sm" />
        ),
      },
      {
        title: t('reviews.col_risk'),
        key: 'risk',
        width: 120,
        render: (_: unknown, item: PendingReviewItem) => {
          const ai = item.ai_analysis;
          return ai ? <RiskPill level={ai.risk_level} score={ai.risk_score} size="sm" /> : '—';
        },
      },
      {
        title: t('reviews.col_datasource'),
        key: 'datasource',
        render: (_: unknown, item: PendingReviewItem) => (
          <span className="mono" style={{ fontSize: 12 }}>{item.datasource.name}</span>
        ),
      },
      {
        title: t('reviews.col_submitter'),
        key: 'submitter',
        render: (_: unknown, item: PendingReviewItem) => (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Avatar name={item.submitted_by.email} size={22} />
            <span style={{ fontSize: 12 }}>{item.submitted_by.email}</span>
          </div>
        ),
      },
      {
        title: t('reviews.col_created'),
        dataIndex: 'created_at',
        key: 'created_at',
        width: 140,
        render: (value: string) => (
          <span className="muted" style={{ fontSize: 12 }}>{timeAgo(value)}</span>
        ),
      },
      {
        title: t('reviews.col_status'),
        key: 'status',
        width: 160,
        render: (_: unknown, item: PendingReviewItem) => {
          const s = rowStatuses[item.id];
          return s ? rowStatusTag(s) : null;
        },
      },
      {
        title: t('reviews.col_actions'),
        key: 'actions',
        width: 200,
        align: 'right' as const,
        render: (_: unknown, item: PendingReviewItem) => (
          <Space size="small">
            <Button
              size="small"
              danger
              icon={<CloseOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                setRejectTargetId(item.id);
              }}
            >
              {t('common.reject')}
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<CheckOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                approve.mutate(item.id);
              }}
            >
              {t('common.approve')}
            </Button>
          </Space>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [t, rowStatuses],
  );

  const onRowClick = (item: PendingReviewItem) => ({
    onClick: () => navigate(`/queries/${item.id}`),
    style: { cursor: 'pointer' as const },
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('reviews.title')}
        subtitle={t('reviews.subtitle')}
        actions={
          <Space>
            <PushApprovalsToggle />
            <Button icon={<ReloadOutlined />} onClick={() => refetch()}>
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        style={{ padding: '0 28px' }}
        items={[
          { key: 'mine', label: t('reviews.tab_mine', { count: Math.min(8, reviewable.length) }) },
          { key: 'all', label: t('reviews.tab_all', { count: reviewable.length }) },
          { key: 'recent', label: t('reviews.tab_recent') },
        ]}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        {selectedRowKeys.length > 0 && (
          <div
            role="toolbar"
            aria-label={t('reviews.bulk.action_bar_count', { count: selectedRowKeys.length })}
            style={{
              position: 'sticky',
              top: 0,
              zIndex: 10,
              background: 'var(--bg-elev)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-md)',
              padding: '10px 14px',
              marginBottom: 12,
              display: 'flex',
              alignItems: 'center',
              gap: 12,
            }}
          >
            <strong>{t('reviews.bulk.action_bar_count', { count: selectedRowKeys.length })}</strong>
            <div style={{ flex: 1 }} />
            <Button
              size="small"
              icon={<EditOutlined />}
              onClick={() => setBulkAction('REQUESTED_CHANGES')}
            >
              {t('reviews.bulk.request_changes_selected')}
            </Button>
            <Button
              size="small"
              danger
              icon={<CloseOutlined />}
              onClick={() => setBulkAction('REJECTED')}
            >
              {t('reviews.bulk.reject_selected')}
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<CheckOutlined />}
              onClick={() => setBulkAction('APPROVED')}
            >
              {t('reviews.bulk.approve_selected')}
            </Button>
            <Button size="small" type="link" onClick={() => setSelectedRowKeys([])}>
              {t('reviews.bulk.clear_selection')}
            </Button>
          </div>
        )}
        {isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : list.length === 0 ? (
          <EmptyState
            title={t('reviews.empty_title')}
            description={t('reviews.empty_description')}
          />
        ) : (
          <Table<PendingReviewItem>
            rowKey="id"
            dataSource={list}
            columns={columns}
            pagination={false}
            size="middle"
            scroll={{ x: 'max-content' }}
            onRow={onRowClick}
            rowSelection={{
              selectedRowKeys,
              onChange: (keys) => setSelectedRowKeys(keys as string[]),
            }}
          />
        )}
      </div>
      <RejectModal
        open={rejectTargetId !== null}
        loading={reject.isPending}
        onCancel={() => setRejectTargetId(null)}
        onConfirm={(comment) => {
          if (!rejectTargetId || !comment) return;
          reject.mutate({ id: rejectTargetId, comment });
        }}
      />
      <BulkDecisionModal
        open={bulkAction !== null}
        decision={bulkAction ?? 'APPROVED'}
        selectedCount={selectedRowKeys.length}
        loading={bulk.isPending}
        onCancel={() => setBulkAction(null)}
        onConfirm={(comment) => {
          if (!bulkAction) return;
          bulk.mutate({ decision: bulkAction, comment });
        }}
      />
    </div>
  );
}
