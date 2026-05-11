import { App, Button, Skeleton, Tabs } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { RiskPill } from '@/components/common/RiskPill';
import { Avatar } from '@/components/common/Avatar';
import { useAuthStore } from '@/store/authStore';
import { timeAgo } from '@/utils/dateFormat';
import { queryKeys } from '@/api/queries';
import {
  approveQuery,
  listPendingReviews,
  rejectQuery,
  reviewKeys,
  type PendingReviewsFilters,
} from '@/api/reviews';
import { reviewErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { PendingReviewItem } from '@/types/api';

export function ReviewQueuePage() {
  const { t } = useTranslation();
  const user = useAuthStore((s) => s.user);
  const [tab, setTab] = useState('mine');
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
    mutationFn: (id: string) => rejectQuery(id),
    onSuccess: (_, id) => {
      invalidateAfterDecision(id);
      message.error(t('reviews.on_reject'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('reviews.title')}
        subtitle={t('reviews.subtitle')}
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => refetch()}>
            {t('common.refresh')}
          </Button>
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
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 24,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(420px, 1fr))',
          gap: 16,
          alignContent: 'start',
        }}
      >
        {isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : (
          <>
            {list.map((q) => (
              <ReviewCard
                key={q.id}
                item={q}
                onOpen={() => navigate(`/queries/${q.id}`)}
                onApprove={() => approve.mutate(q.id)}
                onReject={() => reject.mutate(q.id)}
              />
            ))}
            {list.length === 0 && (
              <EmptyState
                title={t('reviews.empty_title')}
                description={t('reviews.empty_description')}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
}

interface CardProps {
  item: PendingReviewItem;
  onOpen: () => void;
  onApprove: () => void;
  onReject: () => void;
}

function ReviewCard({ item, onOpen, onApprove, onReject }: CardProps) {
  const { t } = useTranslation();
  const ai = item.ai_analysis;
  return (
    <div
      onClick={onOpen}
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 14,
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        cursor: 'pointer',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="mono muted" style={{ fontSize: 11 }}>
          {item.id.slice(0, 8)}
        </span>
        <QueryTypePill type={item.query_type} size="sm" />
        {ai && <RiskPill level={ai.risk_level} score={ai.risk_score} size="sm" />}
        <div style={{ flex: 1 }} />
        <span className="muted" style={{ fontSize: 11 }}>
          {timeAgo(item.created_at)}
        </span>
      </div>
      <div className="muted" style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}>
        {item.id}
      </div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          paddingTop: 10,
          borderTop: '1px solid var(--border)',
        }}
      >
        <Avatar name={item.submitted_by.email} size={22} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 12, fontWeight: 500 }}>{item.submitted_by.email}</div>
          <div className="mono muted" style={{ fontSize: 10 }}>
            {item.datasource.name}
          </div>
        </div>
        <div style={{ flex: 1 }} />
        <Button
          size="small"
          danger
          icon={<CloseOutlined />}
          onClick={(e) => {
            e.stopPropagation();
            onReject();
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
            onApprove();
          }}
        >
          {t('common.approve')}
        </Button>
      </div>
    </div>
  );
}
