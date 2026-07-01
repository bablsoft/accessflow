import { useMemo, useState } from 'react';
import { App, Button, Input, Modal, Skeleton, Table, Tag, Tooltip } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  approveErasure,
  lifecycleKeys,
  listErasureQueue,
  rejectErasure,
  type ErasureListFilters,
} from '@/api/lifecycle';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import { erasureStatusLabel, lifecycleSubjectTypeLabel } from '@/utils/enumLabels';
import { erasureStatusTagColor } from '@/utils/erasureStatus';
import type { ErasureRequest } from '@/types/api';

const PAGE_SIZE = 20;

export default function ErasureReviewQueuePage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [rejectTarget, setRejectTarget] = useState<ErasureRequest | null>(null);
  const [rejectComment, setRejectComment] = useState('');

  const filters: ErasureListFilters = useMemo(() => ({ page, size: PAGE_SIZE }), [page]);

  const queueQuery = useQuery({
    queryKey: lifecycleKeys.erasureQueue(filters),
    queryFn: () => listErasureQueue(filters),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: lifecycleKeys.erasures() });
  };

  const approveMutation = useMutation({
    mutationFn: (id: string) => approveErasure(id),
    onSuccess: () => {
      invalidate();
      message.success(t('lifecycle.erasure_review.approved'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment: string }) => rejectErasure(id, comment),
    onSuccess: () => {
      invalidate();
      setRejectTarget(null);
      setRejectComment('');
      message.error(t('lifecycle.erasure_review.rejected'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const requests = queueQuery.data?.content ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('lifecycle.erasure_review.title')}
        subtitle={t('lifecycle.erasure_review.subtitle_count', {
          count: queueQuery.data?.total_elements ?? 0,
        })}
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => queueQuery.refetch()}>
            {t('common.refresh')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {queueQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : queueQuery.isError ? (
          <EmptyState
            title={t('lifecycle.erasure_review.load_error')}
            description={adminErrorMessage(queueQuery.error)}
          />
        ) : requests.length === 0 ? (
          <EmptyState
            title={t('lifecycle.erasure_review.empty_title')}
            description={t('lifecycle.erasure_review.empty_description')}
          />
        ) : (
          <Table<ErasureRequest>
            rowKey="id"
            size="middle"
            dataSource={requests}
            scroll={{ x: 'max-content' }}
            expandable={{
              expandedRowRender: (r) => (
                <div style={{ fontSize: 12 }}>
                  <div className="muted">{t('lifecycle.erasure_review.scope_label')}</div>
                  <pre className="mono" style={{ whiteSpace: 'pre-wrap', margin: '4px 0 0' }}>
                    {r.scope_snapshot ?? t('lifecycle.erasure_review.no_scope')}
                  </pre>
                </div>
              ),
            }}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: queueQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: t('lifecycle.erasure.col_subject'),
                dataIndex: 'subject_identifier',
                render: (v: string | null, r) => {
                  const conditionCount = r.conditions?.conditions.length ?? 0;
                  const scopeBits: string[] = [];
                  if (conditionCount > 0) {
                    scopeBits.push(t('lifecycle.config.n_conditions', { count: conditionCount }));
                  }
                  if (r.raw_where) scopeBits.push(t('lifecycle.config.has_raw_where'));
                  return (
                    <div>
                      <div style={{ fontSize: 13 }}>{v ?? r.target_table ?? '—'}</div>
                      <div className="mono muted" style={{ fontSize: 11 }}>
                        {r.subject_type
                          ? lifecycleSubjectTypeLabel(t, r.subject_type)
                          : t('lifecycle.config.custom_scope')}
                        {r.datasource_name ? ` · ${r.datasource_name}` : ''}
                      </div>
                      {scopeBits.length > 0 && (
                        <div className="mono muted" style={{ fontSize: 11 }}>
                          {scopeBits.join(' · ')}
                        </div>
                      )}
                    </div>
                  );
                },
              },
              {
                title: t('lifecycle.erasure_review.col_requested_by'),
                dataIndex: 'requested_by_email',
                width: 200,
                render: (v: string | null) => <span className="muted">{v ?? '—'}</span>,
              },
              {
                title: t('lifecycle.erasure.col_status'),
                dataIndex: 'status',
                width: 140,
                render: (status: ErasureRequest['status']) => (
                  <Tag color={erasureStatusTagColor(status)}>{erasureStatusLabel(t, status)}</Tag>
                ),
              },
              {
                title: t('lifecycle.erasure.col_created'),
                dataIndex: 'created_at',
                width: 160,
                render: (v: string) => <span className="muted">{fmtDate(v)}</span>,
              },
              {
                title: t('lifecycle.policies.col_actions'),
                key: 'actions',
                width: 180,
                align: 'right' as const,
                render: (_v, r) => (
                  <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                    <Tooltip title={t('lifecycle.erasure_review.approve')}>
                      <Button
                        size="small"
                        type="primary"
                        icon={<CheckOutlined />}
                        loading={approveMutation.isPending && approveMutation.variables === r.id}
                        onClick={() => approveMutation.mutate(r.id)}
                      >
                        {t('lifecycle.erasure_review.approve')}
                      </Button>
                    </Tooltip>
                    <Tooltip title={t('lifecycle.erasure_review.reject')}>
                      <Button
                        size="small"
                        danger
                        icon={<CloseOutlined />}
                        onClick={() => {
                          setRejectComment('');
                          setRejectTarget(r);
                        }}
                      >
                        {t('lifecycle.erasure_review.reject')}
                      </Button>
                    </Tooltip>
                  </div>
                ),
              },
            ]}
          />
        )}
      </div>

      <Modal
        open={rejectTarget !== null}
        title={t('lifecycle.erasure_review.reject_modal_title')}
        okText={t('lifecycle.erasure_review.reject')}
        okButtonProps={{ danger: true }}
        cancelText={t('common.cancel')}
        confirmLoading={rejectMutation.isPending}
        onCancel={() => setRejectTarget(null)}
        onOk={() =>
          rejectTarget && rejectMutation.mutate({ id: rejectTarget.id, comment: rejectComment })
        }
        destroyOnHidden
      >
        <Input.TextArea
          rows={3}
          maxLength={4000}
          value={rejectComment}
          onChange={(e) => setRejectComment(e.target.value)}
          placeholder={t('lifecycle.erasure_review.reject_comment_placeholder')}
        />
      </Modal>
    </div>
  );
}
