import { useState } from 'react';
import {
  Alert,
  App,
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Skeleton,
  Space,
  Table,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { apiKeysKeys, createApiKey, listApiKeys, revokeApiKey } from '@/api/apiKeys';
import type { ApiKey, CreateApiKeyInput, CreateApiKeyResponse } from '@/types/api';
import { apiErrorTraceId, profileErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';

interface CreateFormValues {
  name: string;
}

export function ApiKeysSection() {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [issuedKey, setIssuedKey] = useState<CreateApiKeyResponse | null>(null);
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);
  const [form] = Form.useForm<CreateFormValues>();

  const listQuery = useQuery({
    queryKey: apiKeysKeys.list,
    queryFn: listApiKeys,
  });

  const createMutation = useMutation({
    mutationFn: (input: CreateApiKeyInput) => createApiKey(input),
    onSuccess: (response) => {
      setError(null);
      setIssuedKey(response);
      setCreateOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: apiKeysKeys.list });
    },
    onError: (err) => setError({ message: profileErrorMessage(err), traceId: apiErrorTraceId(err) }),
  });

  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeApiKey(id),
    onSuccess: () => {
      message.success(t('profile.api_keys.revoked'));
      queryClient.invalidateQueries({ queryKey: apiKeysKeys.list });
    },
    onError: (err) => setError({ message: profileErrorMessage(err), traceId: apiErrorTraceId(err) }),
  });

  const dateFormatter = new Intl.DateTimeFormat(i18n.language, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  const fmtDate = (value: string | null) => (value ? dateFormatter.format(new Date(value)) : '—');

  const columns: ColumnsType<ApiKey> = [
    {
      title: t('profile.api_keys.column.name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <strong>{name}</strong>,
    },
    {
      title: t('profile.api_keys.column.prefix'),
      dataIndex: 'key_prefix',
      key: 'key_prefix',
      render: (prefix: string) => <Typography.Text code>{prefix}…</Typography.Text>,
    },
    {
      title: t('profile.api_keys.column.created_at'),
      dataIndex: 'created_at',
      key: 'created_at',
      render: fmtDate,
    },
    {
      title: t('profile.api_keys.column.last_used_at'),
      dataIndex: 'last_used_at',
      key: 'last_used_at',
      render: fmtDate,
    },
    {
      title: t('profile.api_keys.column.expires_at'),
      dataIndex: 'expires_at',
      key: 'expires_at',
      render: fmtDate,
    },
    {
      title: t('profile.api_keys.column.status'),
      key: 'status',
      render: (_, key) =>
        key.revoked_at
          ? t('profile.api_keys.status.revoked')
          : t('profile.api_keys.status.active'),
    },
    {
      title: '',
      key: 'actions',
      width: 120,
      render: (_, key) =>
        key.revoked_at ? null : (
          <Popconfirm
            title={t('profile.api_keys.revoke_confirm', { name: key.name })}
            okText={t('profile.api_keys.revoke')}
            cancelText={t('common.cancel')}
            onConfirm={() => revokeMutation.mutate(key.id)}
          >
            <Button
              type="link"
              danger
              size="small"
              aria-label={t('profile.api_keys.revoke_aria', { name: key.name })}
              loading={revokeMutation.isPending && revokeMutation.variables === key.id}
            >
              {t('profile.api_keys.revoke')}
            </Button>
          </Popconfirm>
        ),
    },
  ];

  if (listQuery.isLoading) {
    return <Skeleton active />;
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {error && (
        <Alert
          type="error"
          message={error.message}
          description={error.traceId ? <TraceIdFooter traceId={error.traceId} /> : undefined}
          showIcon
          closable
          onClose={() => setError(null)}
        />
      )}

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography.Text type="secondary">{t('profile.api_keys.description')}</Typography.Text>
        <Button type="primary" onClick={() => setCreateOpen(true)}>
          {t('profile.api_keys.create')}
        </Button>
      </div>

      {listQuery.data && listQuery.data.length > 0 ? (
        <Table<ApiKey>
          dataSource={listQuery.data}
          rowKey="id"
          columns={columns}
          pagination={false}
          size="small"
          aria-label={t('profile.api_keys.title')}
        />
      ) : (
        <Empty description={t('profile.api_keys.empty')} />
      )}

      <Modal
        title={t('profile.api_keys.create_title')}
        open={createOpen}
        onCancel={() => {
          setCreateOpen(false);
          form.resetFields();
        }}
        onOk={() => form.submit()}
        confirmLoading={createMutation.isPending}
        okText={t('profile.api_keys.create')}
        cancelText={t('common.cancel')}
        destroyOnHidden
      >
        <Form<CreateFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => createMutation.mutate({ name: values.name })}
        >
          <Form.Item
            name="name"
            label={t('profile.api_keys.name_label')}
            rules={[
              { required: true, message: t('profile.api_keys.name_required') },
              { min: 1, max: 100, message: t('profile.api_keys.name_size') },
            ]}
          >
            <Input placeholder={t('profile.api_keys.name_placeholder')} autoFocus />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('profile.api_keys.issued_title')}
        open={Boolean(issuedKey)}
        onCancel={() => setIssuedKey(null)}
        footer={[
          <Button key="close" type="primary" onClick={() => setIssuedKey(null)}>
            {t('common.close')}
          </Button>,
        ]}
        destroyOnHidden
      >
        {issuedKey && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Alert type="warning" showIcon message={t('profile.api_keys.copy_once_warning')} />
            <Typography.Text strong>{t('profile.api_keys.name_label')}</Typography.Text>
            <Typography.Text>{issuedKey.api_key.name}</Typography.Text>
            <Typography.Text strong>{t('profile.api_keys.raw_key_label')}</Typography.Text>
            <Typography.Paragraph copyable={{ text: issuedKey.raw_key }} code style={{ wordBreak: 'break-all' }}>
              {issuedKey.raw_key}
            </Typography.Paragraph>
          </Space>
        )}
      </Modal>
    </Space>
  );
}
