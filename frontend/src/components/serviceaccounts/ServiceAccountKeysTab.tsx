import { useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  issueServiceAccountKey,
  revokeServiceAccountKey,
  rotateServiceAccountKey,
  serviceAccountKeys,
} from '@/api/serviceAccounts';
import type {
  IssuedServiceAccountKey,
  RotatedServiceAccountKey,
  ServiceAccount,
  ServiceAccountKey,
} from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';
import { serviceAccountErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import {
  KEY_FORM_CONSTRAINTS,
  fieldRules,
  gracePeriodOf,
  keyStatus,
} from '@/pages/admin/service-accounts/serviceAccountForm';
import { ExpiresAtPicker } from './ExpiresAtPicker';
import { IssuedKeyModal } from './IssuedKeyModal';

/** Mirrors `accessflow.serviceaccounts.rotation-grace`'s shipped default (PT24H). */
const DEFAULT_GRACE_HOURS = 24;

interface KeyFormValues {
  name: string;
  expires_at?: Dayjs | null;
  grace_hours?: number | null;
}

export function ServiceAccountKeysTab({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [issueOpen, setIssueOpen] = useState(false);
  const [rotating, setRotating] = useState<ServiceAccountKey | null>(null);
  const [issued, setIssued] = useState<IssuedServiceAccountKey | RotatedServiceAccountKey | null>(null);
  const [issueForm] = Form.useForm<KeyFormValues>();
  const [rotateForm] = Form.useForm<KeyFormValues>();

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: serviceAccountKeys.detail(account.id) });
    void queryClient.invalidateQueries({ queryKey: serviceAccountKeys.lists() });
  };
  const onError = (err: unknown) => showApiError(message, err, serviceAccountErrorMessage);

  const issueMutation = useMutation({
    mutationFn: (values: KeyFormValues) =>
      issueServiceAccountKey(account.id, {
        name: values.name.trim(),
        expires_at: values.expires_at ? values.expires_at.toISOString() : null,
      }),
    onSuccess: (result) => {
      message.success(t('admin.service_accounts.keys.issued'));
      setIssueOpen(false);
      issueForm.resetFields();
      setIssued(result);
      refresh();
    },
    onError,
  });

  const rotateMutation = useMutation({
    mutationFn: ({ key, values }: { key: ServiceAccountKey; values: KeyFormValues }) =>
      rotateServiceAccountKey(account.id, key.id, {
        name: values.name.trim(),
        expires_at: values.expires_at ? values.expires_at.toISOString() : null,
        grace_period: gracePeriodOf(values.grace_hours) ?? null,
      }),
    onSuccess: (result) => {
      message.success(t('admin.service_accounts.keys.rotated'));
      setRotating(null);
      rotateForm.resetFields();
      setIssued(result);
      refresh();
    },
    onError,
  });

  const revokeMutation = useMutation({
    mutationFn: (keyId: string) => revokeServiceAccountKey(account.id, keyId),
    onSuccess: () => {
      message.success(t('admin.service_accounts.keys.revoked'));
      refresh();
    },
    onError,
  });

  const columns: ColumnsType<ServiceAccountKey> = [
    {
      title: t('admin.service_accounts.keys.col_name'),
      dataIndex: 'name',
      render: (name: string, key) => (
        <Space size={6}>
          <span>{name}</span>
          {key.bootstrap_declared && (
            <Tooltip title={t('admin.service_accounts.keys.bootstrap_declared_tooltip')}>
              <Tag color="orange" data-testid="bootstrap-declared-tag">
                {t('admin.service_accounts.keys.bootstrap_declared')}
              </Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: t('admin.service_accounts.keys.col_prefix'),
      dataIndex: 'key_prefix',
      render: (v: string) => <Typography.Text code>{v}…</Typography.Text>,
    },
    {
      title: t('admin.service_accounts.keys.col_created'),
      dataIndex: 'created_at',
      render: (v: string) => fmtDate(v),
    },
    {
      title: t('admin.service_accounts.keys.col_last_used'),
      dataIndex: 'last_used_at',
      render: (v: string | null) => (v ? fmtDate(v) : <span className="muted">—</span>),
    },
    {
      title: t('admin.service_accounts.keys.col_expires'),
      dataIndex: 'expires_at',
      render: (v: string | null) => (v ? fmtDate(v) : <span className="muted">—</span>),
    },
    {
      title: t('admin.service_accounts.keys.col_status'),
      key: 'status',
      render: (_v, key) => {
        const status = keyStatus(key);
        const color = status === 'active' ? 'green' : status === 'revoked' ? 'red' : 'default';
        return <Tag color={color}>{t(`admin.service_accounts.keys.status_${status}`)}</Tag>;
      },
    },
    {
      title: t('admin.service_accounts.keys.col_actions'),
      key: 'actions',
      render: (_v, key) => {
        const status = keyStatus(key);
        if (status === 'revoked') return null;
        const locked = key.bootstrap_declared;
        const lockTooltip = locked ? t('admin.service_accounts.keys.bootstrap_declared_tooltip') : undefined;
        return (
          <Space size={4}>
            <Tooltip title={lockTooltip}>
              <Button
                size="small"
                disabled={locked}
                aria-label={t('admin.service_accounts.keys.rotate')}
                onClick={() => {
                  rotateForm.setFieldsValue({ name: key.name, expires_at: null, grace_hours: null });
                  setRotating(key);
                }}
              >
                {t('admin.service_accounts.keys.rotate')}
              </Button>
            </Tooltip>
            <Popconfirm
              title={t('admin.service_accounts.keys.revoke_confirm', { name: key.name })}
              okText={t('admin.service_accounts.keys.revoke')}
              cancelText={t('common.cancel')}
              okButtonProps={{ danger: true }}
              disabled={locked}
              onConfirm={() => revokeMutation.mutate(key.id)}
            >
              <Tooltip title={lockTooltip}>
                <Button
                  size="small"
                  danger
                  disabled={locked}
                  aria-label={t('admin.service_accounts.keys.revoke')}
                  loading={revokeMutation.isPending && revokeMutation.variables === key.id}
                >
                  {t('admin.service_accounts.keys.revoke')}
                </Button>
              </Tooltip>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  const keyFields = (form: 'issue' | 'rotate') => (
    <>
      {/* Rules derive from KEY_FORM_CONSTRAINTS (createFormParity.test.ts). */}
      <Form.Item
        name="name"
        label={t('admin.service_accounts.keys.name_label')}
        rules={fieldRules(t, KEY_FORM_CONSTRAINTS.name)}
      >
        <Input placeholder={t('admin.service_accounts.keys.name_placeholder')} autoFocus={form === 'issue'} />
      </Form.Item>
      <Form.Item
        name="expires_at"
        label={t('admin.service_accounts.keys.expires_at_label')}
        extra={t('admin.service_accounts.keys.expires_at_help')}
      >
        <ExpiresAtPicker placeholder={t('admin.service_accounts.keys.expires_at_placeholder')} />
      </Form.Item>
    </>
  );

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        {t('admin.service_accounts.keys.description')}
      </Typography.Paragraph>
      <div>
        <Button type="primary" onClick={() => setIssueOpen(true)}>
          {t('admin.service_accounts.keys.issue')}
        </Button>
      </div>
      <Table<ServiceAccountKey>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={account.api_keys}
        pagination={false}
        locale={{ emptyText: t('admin.service_accounts.keys.empty') }}
      />

      <Modal
        title={t('admin.service_accounts.keys.issue_title')}
        open={issueOpen}
        onCancel={() => setIssueOpen(false)}
        onOk={() => issueForm.submit()}
        okText={t('admin.service_accounts.keys.issue')}
        cancelText={t('common.cancel')}
        confirmLoading={issueMutation.isPending}
        destroyOnHidden
      >
        <Form<KeyFormValues>
          form={issueForm}
          name="issueServiceAccountKey"
          layout="vertical"
          onFinish={(values) => issueMutation.mutate(values)}
        >
          {keyFields('issue')}
        </Form>
      </Modal>

      <Modal
        title={t('admin.service_accounts.keys.rotate_title', { name: rotating?.name ?? '' })}
        open={Boolean(rotating)}
        onCancel={() => setRotating(null)}
        onOk={() => rotateForm.submit()}
        okText={t('admin.service_accounts.keys.rotate')}
        cancelText={t('common.cancel')}
        confirmLoading={rotateMutation.isPending}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          {t('admin.service_accounts.keys.rotate_help')}
        </Typography.Paragraph>
        <Form<KeyFormValues>
          form={rotateForm}
          name="rotateServiceAccountKey"
          layout="vertical"
          onFinish={(values) => {
            if (rotating) rotateMutation.mutate({ key: rotating, values });
          }}
        >
          {keyFields('rotate')}
          <Form.Item
            name="grace_hours"
            label={t('admin.service_accounts.keys.label_grace_hours')}
            extra={t('admin.service_accounts.keys.grace_default_help', { hours: DEFAULT_GRACE_HOURS })}
          >
            <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder={String(DEFAULT_GRACE_HOURS)} />
          </Form.Item>
        </Form>
      </Modal>

      <IssuedKeyModal issued={issued} onClose={() => setIssued(null)} />
    </Space>
  );
}
