import { App, Button, Input, Modal, Skeleton, Space, Table, Tag, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Avatar } from '@/components/common/Avatar';
import { timeAgo } from '@/utils/dateFormat';
import { showApiError } from '@/utils/showApiError';
import { reviewErrorMessage } from '@/utils/apiErrors';
import {
  accessRequestKeys,
  approveAccessRequest,
  listPendingAccessRequests,
  rejectAccessRequest,
} from '@/api/accessRequests';
import type { PendingAccessRequestItem } from '@/types/api';

export function AccessRequestsQueuePage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [rejectTarget, setRejectTarget] = useState<string | null>(null);
  const [rejectComment, setRejectComment] = useState('');

  const { data, isLoading, refetch } = useQuery({
    queryKey: accessRequestKeys.queueFor({ size: 50 }),
    queryFn: () => listPendingAccessRequests({ size: 50 }),
  });

  const invalidate = () => void queryClient.invalidateQueries({ queryKey: accessRequestKeys.all });

  const approve = useMutation({
    mutationFn: (id: string) => approveAccessRequest(id),
    onSuccess: () => {
      invalidate();
      message.success(t('access.queue.on_approve'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const reject = useMutation({
    mutationFn: ({ id, comment }: { id: string; comment: string }) => rejectAccessRequest(id, comment),
    onSuccess: () => {
      invalidate();
      setRejectTarget(null);
      setRejectComment('');
      message.error(t('access.queue.on_reject'));
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const capabilityTags = (r: PendingAccessRequestItem) => (
    <Space size={4} wrap>
      {r.can_read && <Tag>{t('access.request.can_read')}</Tag>}
      {r.can_write && <Tag color="orange">{t('access.request.can_write')}</Tag>}
      {r.can_ddl && <Tag color="red">{t('access.request.can_ddl')}</Tag>}
      {r.pre_approve_queries && (
        <Tooltip title={t('access.queue.pre_approve_tooltip')}>
          <Tag color="blue">{t('access.request.pre_approve_tag')}</Tag>
        </Tooltip>
      )}
      {(r.allowed_operations?.length ?? 0) > 0 && (
        <Tooltip title={(r.allowed_operations ?? []).join(', ')}>
          <Tag color="purple">
            {t('access.request.operations_tag', { count: r.allowed_operations?.length ?? 0 })}
          </Tag>
        </Tooltip>
      )}
    </Space>
  );

  const columns: TableColumnsType<PendingAccessRequestItem> = [
    {
      title: t('access.queue.requester'),
      render: (_v, r) => (
        <Space>
          <Avatar name={r.requested_by.email ?? r.requested_by.id} size={24} />
          <span>{r.requested_by.email ?? r.requested_by.id}</span>
        </Space>
      ),
    },
    {
      title: t('access.queue.resource'),
      render: (_v, r) => (
        <Space size={6} wrap>
          {r.resource_kind === 'API_CONNECTOR' ? (
            <Tag color="geekblue">{t('access.kind.api_connector')}</Tag>
          ) : (
            <Tag>{t('access.kind.datasource')}</Tag>
          )}
          <span>{r.connector?.name ?? r.datasource?.name ?? r.connector?.id ?? r.datasource?.id}</span>
        </Space>
      ),
    },
    { title: t('access.queue.capabilities'), render: (_v, r) => capabilityTags(r) },
    {
      title: t('access.queue.duration'),
      dataIndex: 'requested_duration',
      render: (v: string) => <span className="mono">{v}</span>,
    },
    {
      title: t('access.queue.justification'),
      dataIndex: 'justification',
      ellipsis: true,
      render: (v: string) => <Tooltip title={v}>{v}</Tooltip>,
    },
    {
      title: t('access.queue.requested'),
      dataIndex: 'created_at',
      render: (v: string) => <span className="muted">{timeAgo(v)}</span>,
    },
    {
      title: '',
      key: 'actions',
      align: 'right',
      render: (_v, r) => (
        <Space>
          <Button
            size="small"
            type="primary"
            icon={<CheckOutlined />}
            loading={approve.isPending}
            onClick={() => approve.mutate(r.id)}
          >
            {t('access.queue.approve')}
          </Button>
          <Button
            size="small"
            danger
            icon={<CloseOutlined />}
            onClick={() => setRejectTarget(r.id)}
          >
            {t('access.queue.reject')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title={t('access.queue.title')}
        subtitle={t('access.queue.subtitle')}
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => refetch()}>
            {t('common.refresh')}
          </Button>
        }
      />

      {isLoading ? (
        <Skeleton active />
      ) : (data?.content.length ?? 0) === 0 ? (
        <EmptyState title={t('access.queue.empty')} />
      ) : (
        <Table
          rowKey="id"
          columns={columns}
          dataSource={data?.content ?? []}
          pagination={false}
          size="middle"
        />
      )}

      <Modal
        open={rejectTarget !== null}
        title={t('access.queue.reject_reason')}
        okText={t('access.queue.reject')}
        okButtonProps={{ danger: true, disabled: rejectComment.trim().length === 0 }}
        confirmLoading={reject.isPending}
        onOk={() => {
          if (rejectTarget && rejectComment.trim()) {
            reject.mutate({ id: rejectTarget, comment: rejectComment.trim() });
          }
        }}
        onCancel={() => {
          setRejectTarget(null);
          setRejectComment('');
        }}
      >
        <Input.TextArea
          rows={3}
          value={rejectComment}
          onChange={(e) => setRejectComment(e.target.value)}
          placeholder={t('access.queue.reject_required')}
          maxLength={4000}
        />
      </Modal>
    </div>
  );
}
