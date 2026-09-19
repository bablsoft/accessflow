import { useEffect, useState } from 'react';
import { App, Button, Form, Input, InputNumber, Modal, Select, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { ManagedByTag } from '@/components/common/ManagedByTag';
import { Pill } from '@/components/common/Pill';
import { RolePill } from '@/components/common/RolePill';
import { renderUserOption } from '@/components/common/renderUserOption';
import { RoleField } from '@/components/serviceaccounts/RoleField';
import { useHumanUserOptions } from '@/components/serviceaccounts/useHumanUsers';
import {
  createServiceAccount,
  listMcpTools,
  listServiceAccounts,
  serviceAccountKeys,
} from '@/api/serviceAccounts';
import { listRoles, roleKeys } from '@/api/roles';
import { setupProgressKeys } from '@/api/admin';
import { SERVICE_ACCOUNT_SOURCES, enumOptions, serviceAccountSourceLabel } from '@/utils/enumLabels';
import { timeAgo } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';
import { serviceAccountErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { ServiceAccount, ServiceAccountSource } from '@/types/api';
import {
  CREATE_FORM_CONSTRAINTS,
  allowedToolCount,
  createInputFromForm,
  fieldRules,
  type CreateFormValues,
} from './serviceAccountForm';

const PAGE_SIZE = 20;

export function ServiceAccountsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<CreateFormValues>();
  const selectedRoleId = Form.useWatch('role_id', form);
  const [page, setPage] = useState(0);
  const [managedBy, setManagedBy] = useState<ServiceAccountSource | undefined>(undefined);

  const filters = { page, size: PAGE_SIZE, ...(managedBy ? { managed_by: managedBy } : {}) };
  const listQuery = useQuery({
    queryKey: serviceAccountKeys.list(filters),
    queryFn: () => listServiceAccounts(filters),
  });
  const catalogQuery = useQuery({
    queryKey: serviceAccountKeys.mcpTools(),
    queryFn: listMcpTools,
  });
  const rolesQuery = useQuery({
    queryKey: roleKeys.lists(),
    queryFn: listRoles,
    enabled: createOpen,
  });
  const owners = useHumanUserOptions(createOpen);
  const roles = rolesQuery.data ?? [];
  const defaultRoleId = roles.find((role) => role.system && role.name === 'READONLY')?.id;
  // Roles arrive after the modal mounts, so the READONLY default is applied once they do (the
  // UsersPage idiom) — never overriding a role the operator already picked.
  useEffect(() => {
    if (createOpen && defaultRoleId && !form.getFieldValue('role_id')) {
      form.setFieldsValue({ role_id: defaultRoleId });
    }
  }, [createOpen, defaultRoleId, form]);

  const createMutation = useMutation({
    mutationFn: (values: CreateFormValues) => createServiceAccount(createInputFromForm(values)),
    onSuccess: (created) => {
      message.success(t('admin.service_accounts.create_modal.success'));
      setCreateOpen(false);
      form.resetFields();
      void queryClient.invalidateQueries({ queryKey: serviceAccountKeys.lists() });
      void queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      navigate(`/admin/service-accounts/${created.id}`);
    },
    onError: (err) => showApiError(message, err, serviceAccountErrorMessage),
  });

  const rateLimit = (row: ServiceAccount): string => {
    const parts: string[] = [];
    if (row.rate_limit_per_minute !== null) {
      parts.push(t('admin.service_accounts.rate_per_minute', { count: row.rate_limit_per_minute }));
    }
    if (row.rate_limit_per_day !== null) {
      parts.push(t('admin.service_accounts.rate_per_day', { count: row.rate_limit_per_day }));
    }
    return parts.length > 0 ? parts.join(' · ') : t('admin.service_accounts.rate_limit_default');
  };

  const columns: TableColumnsType<ServiceAccount> = [
    {
      title: t('admin.service_accounts.col_name'),
      dataIndex: 'display_name',
      width: 200,
      render: (v: string) => <strong>{v}</strong>,
    },
    {
      title: t('admin.service_accounts.col_email'),
      dataIndex: 'email',
      ellipsis: true,
      render: (v: string) => (
        <span className="mono" style={{ fontSize: 12 }}>
          {v}
        </span>
      ),
    },
    {
      title: t('admin.service_accounts.col_role'),
      dataIndex: 'role_name',
      width: 130,
      render: (v: string) => <RolePill role={v} size="sm" />,
    },
    {
      title: t('admin.service_accounts.col_owner'),
      key: 'owner',
      width: 180,
      ellipsis: true,
      render: (_v, row) =>
        row.owner_user_id ? (
          userDisplay(row.owner_display_name, row.owner_email) || row.owner_user_id
        ) : (
          <span className="muted">{t('admin.service_accounts.no_owner')}</span>
        ),
    },
    {
      title: t('admin.service_accounts.col_status'),
      dataIndex: 'active',
      width: 110,
      render: (v: boolean) =>
        v ? (
          <Pill
            fg="var(--risk-low)"
            bg="var(--risk-low-bg)"
            border="var(--risk-low-border)"
            withDot
            size="sm"
          >
            {t('admin.service_accounts.status_active')}
          </Pill>
        ) : (
          <Pill
            fg="var(--fg-muted)"
            bg="var(--status-neutral-bg)"
            border="var(--status-neutral-border)"
            size="sm"
          >
            {t('admin.service_accounts.status_inactive')}
          </Pill>
        ),
    },
    {
      title: t('admin.service_accounts.col_managed_by'),
      dataIndex: 'managed_by',
      width: 120,
      render: (v: ServiceAccountSource) => <ManagedByTag managedBy={v} />,
    },
    {
      title: t('admin.service_accounts.col_keys'),
      dataIndex: 'active_api_key_count',
      width: 100,
      align: 'center' as const,
    },
    {
      title: t('admin.service_accounts.col_last_used'),
      dataIndex: 'last_used_at',
      width: 130,
      render: (v: string | null) =>
        v ? timeAgo(v) : <span className="muted">{t('admin.service_accounts.never_used')}</span>,
    },
    {
      title: t('admin.service_accounts.col_tools'),
      key: 'tools',
      width: 130,
      render: (_v, row) => {
        const count = allowedToolCount(row, catalogQuery.data);
        if (count === null) return t('admin.service_accounts.tools_all');
        if (count.allowed === 0) return t('admin.service_accounts.tools_none');
        return t('admin.service_accounts.tools_count', count);
      },
    },
    {
      title: t('admin.service_accounts.col_rate_limit'),
      key: 'rate_limit',
      width: 170,
      render: (_v, row) => rateLimit(row),
    },
  ];

  const content = listQuery.data?.content ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.service_accounts.title')}
        subtitle={t('admin.service_accounts.subtitle')}
        docsAnchor="cfg-service-accounts"
        actions={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            {t('admin.service_accounts.create')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '12px' }}>
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <Select<ServiceAccountSource | 'all'>
            aria-label={t('admin.service_accounts.col_managed_by')}
            value={managedBy ?? 'all'}
            style={{ width: 200 }}
            onChange={(value) => {
              setManagedBy(value === 'all' ? undefined : value);
              setPage(0);
            }}
            options={[
              { value: 'all', label: t('admin.service_accounts.filter_all_sources') },
              ...enumOptions(SERVICE_ACCOUNT_SOURCES, serviceAccountSourceLabel, t),
            ]}
          />
        </div>
        {listQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : listQuery.isError ? (
          <EmptyState
            title={t('admin.service_accounts.load_error')}
            action={<Button onClick={() => listQuery.refetch()}>{t('common.retry')}</Button>}
          />
        ) : content.length === 0 && !managedBy ? (
          <EmptyState
            title={t('admin.service_accounts.empty_title')}
            description={t('admin.service_accounts.empty_description')}
            action={
              <Button type="primary" onClick={() => setCreateOpen(true)}>
                {t('admin.service_accounts.create')}
              </Button>
            }
          />
        ) : (
          <Table<ServiceAccount>
            rowKey="id"
            dataSource={content}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('admin.service_accounts.empty_title') }}
            onRow={(row) => ({
              onClick: () => navigate(`/admin/service-accounts/${row.id}`),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: listQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </div>
      <Modal
        open={createOpen}
        title={t('admin.service_accounts.create_modal.title')}
        onCancel={() => setCreateOpen(false)}
        okText={t('admin.service_accounts.create_modal.submit')}
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form<CreateFormValues>
          form={form}
          name="createServiceAccount"
          layout="vertical"
          onFinish={(values) => createMutation.mutate(values)}
        >
          {/* Rules derive from CREATE_FORM_CONSTRAINTS, asserted against
              CreateServiceAccountRequest.java by createFormParity.test.ts. */}
          <Form.Item
            name="email"
            label={t('admin.service_accounts.create_modal.label_email')}
            extra={t('admin.service_accounts.create_modal.email_help')}
            rules={fieldRules(t, CREATE_FORM_CONSTRAINTS.email)}
          >
            <Input autoFocus />
          </Form.Item>
          <Form.Item
            name="display_name"
            label={t('admin.service_accounts.create_modal.label_display_name')}
            rules={fieldRules(t, CREATE_FORM_CONSTRAINTS.display_name)}
          >
            <Input />
          </Form.Item>
          <RoleField
            roles={roles}
            loading={rolesQuery.isLoading}
            selectedRoleId={selectedRoleId ?? defaultRoleId}
            extra={t('admin.service_accounts.create_modal.role_help')}
          />
          <Form.Item
            name="owner_user_id"
            label={t('admin.service_accounts.create_modal.label_owner')}
            extra={t('admin.service_accounts.create_modal.owner_help')}
          >
            <Select
              allowClear
              showSearch={{ optionFilterProp: 'label' }}
              placeholder={t('admin.service_accounts.create_modal.owner_placeholder')}
              loading={owners.loading}
              options={owners.options}
              optionRender={renderUserOption}
            />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('admin.service_accounts.create_modal.label_description')}
            rules={fieldRules(t, CREATE_FORM_CONSTRAINTS.description)}
          >
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item
            name="rate_limit_per_minute"
            label={t('admin.service_accounts.create_modal.label_rate_limit_per_minute')}
            extra={t('admin.service_accounts.create_modal.rate_limit_help')}
            rules={fieldRules(t, CREATE_FORM_CONSTRAINTS.rate_limit_per_minute)}
          >
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="rate_limit_per_day"
            label={t('admin.service_accounts.create_modal.label_rate_limit_per_day')}
            rules={fieldRules(t, CREATE_FORM_CONSTRAINTS.rate_limit_per_day)}
          >
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
