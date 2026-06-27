import { App, Button, Input, Modal, Table, Tag } from 'antd';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  apiRequestKeys,
  approveApiReview,
  listPendingApiReviews,
  rejectApiReview,
} from '@/api/apiRequests';
import { riskLevelLabel } from '@/utils/enumLabels';
import type { PendingApiReview } from '@/types/api';

export default function ApiReviewQueuePage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [decisionFor, setDecisionFor] = useState<{ id: string; kind: 'approve' | 'reject' } | null>(
    null,
  );
  const [comment, setComment] = useState('');

  const queueQuery = useQuery({
    queryKey: apiRequestKeys.reviewQueue({ size: 100 }),
    queryFn: () => listPendingApiReviews({ size: 100 }),
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: apiRequestKeys.reviewQueue({ size: 100 }) });

  const decideMutation = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: 'approve' | 'reject' }) =>
      kind === 'approve' ? approveApiReview(id, comment) : rejectApiReview(id, comment),
    onSuccess: (_data, vars) => {
      message.success(vars.kind === 'approve' ? t('apiGov.reviews.approved') : t('apiGov.reviews.rejected'));
      setDecisionFor(null);
      setComment('');
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const columns = [
    { title: t('apiGov.requests.connector'), dataIndex: 'connector_name' },
    { title: t('apiGov.requests.verb'), dataIndex: 'verb' },
    { title: t('apiGov.requests.path'), dataIndex: 'request_path', ellipsis: true },
    {
      title: t('apiGov.reviews.risk'),
      dataIndex: 'ai_risk_level',
      render: (_: unknown, row: PendingApiReview) =>
        row.ai_risk_level ? (
          <Tag>{riskLevelLabel(t, row.ai_risk_level)} · {row.ai_risk_score ?? '—'}</Tag>
        ) : (
          '—'
        ),
    },
    {
      title: t('common.approve'),
      key: 'actions',
      render: (_: unknown, row: PendingApiReview) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            type="primary"
            onClick={() => setDecisionFor({ id: row.api_request_id, kind: 'approve' })}
          >
            {t('apiGov.reviews.approve')}
          </Button>
          <Button
            size="small"
            danger
            onClick={() => setDecisionFor({ id: row.api_request_id, kind: 'reject' })}
          >
            {t('apiGov.reviews.reject')}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('apiGov.reviews.title')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        <Table<PendingApiReview>
          rowKey="api_request_id"
          loading={queueQuery.isLoading}
          dataSource={queueQuery.data?.content ?? []}
          columns={columns}
          pagination={false}
          locale={{ emptyText: t('apiGov.reviews.empty') }}
        />
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
