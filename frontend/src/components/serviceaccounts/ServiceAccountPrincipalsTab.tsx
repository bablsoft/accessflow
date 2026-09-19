import { useState } from 'react';
import { App, Button, Form, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  grantDelegatedPrincipal,
  listDelegatedPrincipals,
  revokeDelegatedPrincipal,
  serviceAccountKeys,
} from '@/api/serviceAccounts';
import { renderUserOption } from '@/components/common/renderUserOption';
import type { ServiceAccount, ServiceAccountDelegation } from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';
import { serviceAccountDelegationStatusLabel } from '@/utils/enumLabels';
import { serviceAccountErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { ExpiresAtPicker } from './ExpiresAtPicker';
import { useHumanUserOptions } from './useHumanUsers';

interface GrantFormValues {
  principal_user_id: string;
  expires_at?: Dayjs | null;
}

const STATUS_COLORS: Record<ServiceAccountDelegation['status'], string> = {
  ACTIVE: 'green',
  EXPIRED: 'default',
  REVOKED: 'red',
};

export function ServiceAccountPrincipalsTab({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [grantOpen, setGrantOpen] = useState(false);
  const [form] = Form.useForm<GrantFormValues>();
  const people = useHumanUserOptions(grantOpen);

  const listQuery = useQuery({
    queryKey: serviceAccountKeys.delegations(account.id),
    queryFn: () => listDelegatedPrincipals(account.id),
  });
  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: serviceAccountKeys.delegations(account.id) });
  const onError = (err: unknown) => showApiError(message, err, serviceAccountErrorMessage);

  const grantMutation = useMutation({
    mutationFn: (values: GrantFormValues) =>
      grantDelegatedPrincipal(account.id, {
        principal_user_id: values.principal_user_id,
        expires_at: values.expires_at ? values.expires_at.toISOString() : null,
      }),
    onSuccess: () => {
      message.success(t('admin.service_accounts.principals.success'));
      setGrantOpen(false);
      form.resetFields();
      void refresh();
    },
    onError,
  });

  const revokeMutation = useMutation({
    mutationFn: (delegationId: string) => revokeDelegatedPrincipal(account.id, delegationId),
    onSuccess: () => {
      message.success(t('admin.service_accounts.principals.revoked'));
      void refresh();
    },
    onError,
  });

  const columns: ColumnsType<ServiceAccountDelegation> = [
    { title: t('admin.service_accounts.principals.col_principal'), dataIndex: 'principal_email' },
    {
      title: t('admin.service_accounts.principals.col_granted_by'),
      dataIndex: 'granted_by',
      render: (grantedBy: string, row) =>
        grantedBy === row.principal_user_id
          ? t('admin.service_accounts.principals.granted_self')
          : t('admin.service_accounts.principals.granted_admin'),
    },
    {
      title: t('admin.service_accounts.principals.col_created'),
      dataIndex: 'created_at',
      render: (v: string) => fmtDate(v),
    },
    {
      title: t('admin.service_accounts.principals.col_expires'),
      dataIndex: 'expires_at',
      render: (v: string | null) => (v ? fmtDate(v) : <span className="muted">—</span>),
    },
    {
      title: t('admin.service_accounts.principals.col_status'),
      dataIndex: 'status',
      render: (status: ServiceAccountDelegation['status']) => (
        <Tag color={STATUS_COLORS[status]}>{serviceAccountDelegationStatusLabel(t, status)}</Tag>
      ),
    },
    {
      title: t('admin.service_accounts.principals.col_actions'),
      key: 'actions',
      render: (_v, row) =>
        row.status === 'ACTIVE' ? (
          <Popconfirm
            title={t('admin.service_accounts.principals.revoke_confirm', { email: row.principal_email })}
            okText={t('admin.service_accounts.principals.revoke')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true }}
            onConfirm={() => revokeMutation.mutate(row.id)}
          >
            <Button size="small" danger aria-label={t('admin.service_accounts.principals.revoke')}>
              {t('admin.service_accounts.principals.revoke')}
            </Button>
          </Popconfirm>
        ) : null,
    },
  ];

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        {t('admin.service_accounts.principals.description')}
      </Typography.Paragraph>
      <div>
        <Button type="primary" onClick={() => setGrantOpen(true)}>
          {t('admin.service_accounts.principals.grant')}
        </Button>
      </div>
      <Table<ServiceAccountDelegation>
        rowKey="id"
        size="small"
        loading={listQuery.isLoading}
        columns={columns}
        dataSource={listQuery.data ?? []}
        pagination={false}
        locale={{ emptyText: t('admin.service_accounts.principals.empty') }}
      />
      <Modal
        title={t('admin.service_accounts.principals.grant_title')}
        open={grantOpen}
        onCancel={() => setGrantOpen(false)}
        onOk={() => form.submit()}
        okText={t('admin.service_accounts.principals.submit')}
        cancelText={t('common.cancel')}
        confirmLoading={grantMutation.isPending}
        destroyOnHidden
      >
        <Form<GrantFormValues>
          form={form}
          name="grantDelegatedPrincipal"
          layout="vertical"
          onFinish={(values) => grantMutation.mutate(values)}
        >
          {/* Parity with GrantDelegatedPrincipalRequest: principal_user_id @NotNull. */}
          <Form.Item
            name="principal_user_id"
            label={t('admin.service_accounts.principals.label_principal')}
            rules={[{ required: true, message: t('admin.service_accounts.validation.required') }]}
          >
            <Select
              showSearch={{ optionFilterProp: 'label' }}
              placeholder={t('admin.service_accounts.principals.principal_placeholder')}
              loading={people.loading}
              options={people.options}
              optionRender={renderUserOption}
            />
          </Form.Item>
          <Form.Item name="expires_at" label={t('admin.service_accounts.principals.label_expires_at')}>
            <ExpiresAtPicker placeholder={t('admin.service_accounts.principals.expires_at_placeholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
