import { useMemo, useState } from 'react';
import { App, Button, Input, Modal, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { RiskPill } from '@/components/common/RiskPill';
import { RequestGroupItemStatusPill } from '@/components/common/RequestGroupItemStatusPill';
import {
  approveRequestGroup,
  getRequestGroup,
  listPendingGroupReviews,
  rejectRequestGroup,
  requestGroupKeys,
} from '@/api/requestGroups';
import { targetKindLabel } from '@/utils/enumLabels';
import { timeAgo } from '@/utils/dateFormat';
import type { PendingGroupReview, RequestGroupItem } from '@/types/api';

const PAGE_SIZE = 20;

function ExpandedMembers({ groupId }: { groupId: string }) {
  const { t } = useTranslation();
  const detailQuery = useQuery({
    queryKey: requestGroupKeys.detail(groupId),
    queryFn: () => getRequestGroup(groupId),
  });

  if (detailQuery.isLoading) {
    return <Skeleton active paragraph={{ rows: 2 }} />;
  }
  const items = [...(detailQuery.data?.items ?? [])].sort(
    (a, b) => a.sequence_order - b.sequence_order,
  );
  const memberColumns: TableColumnsType<RequestGroupItem> = [
    {
      title: t('requestGroups.reviews.order'),
      dataIndex: 'sequence_order',
      width: 70,
      render: (v: number) => <span className="mono">#{v + 1}</span>,
    },
    {
      title: t('requestGroups.reviews.kind'),
      dataIndex: 'target_kind',
      width: 120,
      render: (_v, r) => targetKindLabel(t, r.target_kind),
    },
    {
      title: t('requestGroups.reviews.target'),
      render: (_v, r) =>
        r.target_kind === 'QUERY' ? (
          <span className="mono" style={{ fontSize: 12 }}>
            {r.datasource_name ?? r.datasource_id}
          </span>
        ) : (
          <span className="mono" style={{ fontSize: 12 }}>
            {r.verb} {r.request_path}
          </span>
        ),
    },
    {
      title: t('requestGroups.reviews.memberRisk'),
      width: 120,
      render: (_v, r) =>
        r.ai_risk_level != null ? (
          <RiskPill level={r.ai_risk_level} score={r.ai_risk_score} size="sm" />
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      title: t('requestGroups.reviews.memberStatus'),
      width: 120,
      render: (_v, r) => <RequestGroupItemStatusPill status={r.status} size="sm" />,
    },
  ];
  return (
    <Table<RequestGroupItem>
      rowKey="id"
      size="small"
      pagination={false}
      dataSource={items}
      columns={memberColumns}
    />
  );
}

export default function RequestGroupReviewQueuePage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [decisionFor, setDecisionFor] = useState<{ id: string; kind: 'approve' | 'reject' } | null>(
    null,
  );
  const [comment, setComment] = useState('');
  const [page, setPage] = useState(0);

  const filters = useMemo(() => ({ page, size: PAGE_SIZE }), [page]);

  const queueQuery = useQuery({
    queryKey: requestGroupKeys.reviews(filters),
    queryFn: () => listPendingGroupReviews(filters),
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ['request-groups', 'reviews'] });

  const decideMutation = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'approve' | 'reject' }) =>
      kind === 'approve' ? approveRequestGroup(id, comment) : rejectRequestGroup(id, comment),
    onSuccess: (_data, vars) => {
      message.success(
        vars.kind === 'approve'
          ? t('requestGroups.reviews.approved')
          : t('requestGroups.reviews.rejected'),
      );
      setDecisionFor(null);
      setComment('');
      invalidate();
    },
    onError: () => message.error(t('requestGroups.error')),
  });

  const rows = useMemo(() => queueQuery.data?.content ?? [], [queueQuery.data]);

  const columns: TableColumnsType<PendingGroupReview> = [
    {
      title: t('requestGroups.reviews.name'),
      dataIndex: 'name',
      render: (v: string) => <span style={{ fontWeight: 600 }}>{v}</span>,
    },
    { title: t('requestGroups.reviews.members'), dataIndex: 'member_count', width: 100 },
    {
      title: t('requestGroups.reviews.risk'),
      width: 120,
      render: (_v, r) =>
        r.ai_risk_level != null ? (
          <RiskPill level={r.ai_risk_level} score={r.ai_risk_score} />
        ) : (
          <span className="muted" style={{ fontSize: 11 }}>
            —
          </span>
        ),
    },
    {
      title: t('requestGroups.reviews.submitter'),
      width: 160,
      ellipsis: true,
      render: (_v, r) => (
        <span style={{ fontSize: 12 }}>
          {r.submitted_by_display_name ?? r.submitted_by_user_id.slice(0, 8)}
        </span>
      ),
    },
    {
      title: t('requestGroups.reviews.created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => (
        <span className="muted" style={{ fontSize: 12 }}>
          {timeAgo(v)}
        </span>
      ),
    },
    {
      title: t('requestGroups.reviews.actions'),
      key: 'actions',
      width: 220,
      render: (_v, row) => (
        <span style={{ display: 'flex', gap: 8 }} onClick={(e) => e.stopPropagation()}>
          <Button size="small" onClick={() => navigate(`/request-groups/${row.request_group_id}`)}>
            {t('requestGroups.reviews.view')}
          </Button>
          <Button
            size="small"
            type="primary"
            onClick={() => setDecisionFor({ id: row.request_group_id, kind: 'approve' })}
          >
            {t('requestGroups.reviews.approve')}
          </Button>
          <Button
            size="small"
            danger
            onClick={() => setDecisionFor({ id: row.request_group_id, kind: 'reject' })}
          >
            {t('requestGroups.reviews.reject')}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('requestGroups.reviews.title')} subtitle={t('requestGroups.reviews.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {queueQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table<PendingGroupReview>
            rowKey="request_group_id"
            dataSource={rows}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('requestGroups.reviews.empty') }}
            expandable={{
              expandedRowRender: (record) => (
                <ExpandedMembers groupId={record.request_group_id} />
              ),
            }}
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
        title={
          decisionFor?.kind === 'approve'
            ? t('requestGroups.reviews.approve')
            : t('requestGroups.reviews.reject')
        }
        onCancel={() => setDecisionFor(null)}
        confirmLoading={decideMutation.isPending}
        onOk={() => decisionFor && decideMutation.mutate(decisionFor)}
      >
        <Input.TextArea
          rows={3}
          placeholder={t('requestGroups.reviews.comment')}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </Modal>
    </div>
  );
}
