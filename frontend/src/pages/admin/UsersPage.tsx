import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Dropdown,
  Form,
  Input,
  Modal,
  Select,
  Skeleton,
  Switch,
  Table,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Avatar } from '@/components/common/Avatar';
import { RolePill } from '@/components/common/RolePill';
import { Pill } from '@/components/common/Pill';
import {
  createInvitation,
  createUser,
  deactivateUser,
  invitationKeys,
  listUsers,
  listInvitations,
  resendInvitation,
  revokeInvitation,
  updateUser,
  userKeys,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';
import type {
  AuthProvider,
  CreateUserInput,
  InviteUserInput,
  Role,
  UpdateUserInput,
  User,
  UserInvitation,
} from '@/types/api';

interface InviteFormValues {
  email: string;
  password: string;
  display_name?: string;
  role: Role;
}

interface InviteByEmailFormValues {
  email: string;
  display_name?: string;
  role: Role;
}

interface EditFormValues {
  role: Role;
  active: boolean;
  display_name?: string;
}

const PAGE_SIZE = 20;

export function UsersPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<'all' | Role>('all');
  const [providerFilter, setProviderFilter] = useState<'all' | AuthProvider>('all');
  const [inviting, setInviting] = useState(false);
  const [invitingByEmail, setInvitingByEmail] = useState(false);
  const [editing, setEditing] = useState<User | null>(null);

  const filters = useMemo(
    () => ({ page, size: PAGE_SIZE, sort: 'email,asc' as const }),
    [page],
  );
  const usersQuery = useQuery({
    queryKey: userKeys.list(filters),
    queryFn: () => listUsers(filters),
  });

  const createMutation = useMutation({
    mutationFn: (payload: CreateUserInput) => createUser(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: userKeys.all });
      message.success(t('admin.users.create_success'));
      setInviting(false);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: (vars: { id: string; payload: UpdateUserInput }) =>
      updateUser(vars.id, vars.payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: userKeys.all });
      message.success(t('admin.users.update_success'));
      setEditing(null);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const deactivateMutation = useMutation({
    mutationFn: (id: string) => deactivateUser(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: userKeys.all });
      message.success(t('admin.users.deactivate_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const invitationsQuery = useQuery({
    queryKey: invitationKeys.list({ page: 0, size: 50, sort: 'createdAt,desc' }),
    queryFn: () => listInvitations({ page: 0, size: 50, sort: 'createdAt,desc' }),
  });

  const inviteMutation = useMutation({
    mutationFn: (payload: InviteUserInput) => createInvitation(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: invitationKeys.all });
      message.success(t('admin.users.invite_email_success'));
      setInvitingByEmail(false);
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const resendMutation = useMutation({
    mutationFn: (id: string) => resendInvitation(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: invitationKeys.all });
      message.success(t('admin.users.invite_resend_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const revokeMutation = useMutation({
    mutationFn: (id: string) => revokeInvitation(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: invitationKeys.all });
      message.success(t('admin.users.invite_revoke_success'));
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  // Server-side pagination + sort. Role / provider / search filters are applied client-side
  // because the backend `/admin/users` endpoint only accepts page, size, and sort today.
  const filtered = useMemo(() => {
    const all = usersQuery.data?.content ?? [];
    return all.filter((u) => {
      if (roleFilter !== 'all' && u.role !== roleFilter) return false;
      if (providerFilter !== 'all' && u.auth_provider !== providerFilter) return false;
      if (
        search &&
        !u.email.toLowerCase().includes(search.toLowerCase()) &&
        !u.display_name.toLowerCase().includes(search.toLowerCase())
      ) {
        return false;
      }
      return true;
    });
  }, [usersQuery.data, search, roleFilter, providerFilter]);

  const onConfirmDeactivate = (user: User) =>
    modal.confirm({
      title: t('admin.users.deactivate_confirm_title'),
      content: t('admin.users.deactivate_confirm_body', { email: user.email }),
      okType: 'danger',
      okText: t('admin.users.deactivate_action'),
      cancelText: t('common.cancel'),
      onOk: () => deactivateMutation.mutateAsync(user.id),
    });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.users.title')}
        subtitle={t('admin.users.subtitle_count', {
          count: usersQuery.data?.total_elements ?? 0,
        })}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => usersQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Dropdown.Button
              type="primary"
              icon={<PlusOutlined />}
              menu={{
                items: [
                  {
                    key: 'password',
                    label: t('admin.users.create_with_password'),
                    onClick: () => setInviting(true),
                  },
                ],
              }}
              onClick={() => setInvitingByEmail(true)}
            >
              {t('admin.users.invite_via_email')}
            </Dropdown.Button>
          </>
        }
      />
      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          gap: 8,
        }}
      >
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder={t('admin.users.search_placeholder')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ width: 280 }}
        />
        <Select
          value={roleFilter}
          onChange={setRoleFilter}
          style={{ width: 130 }}
          options={[
            { value: 'all', label: t('admin.users.filter_all_roles') },
            { value: 'ADMIN', label: 'ADMIN' },
            { value: 'REVIEWER', label: 'REVIEWER' },
            { value: 'ANALYST', label: 'ANALYST' },
            { value: 'READONLY', label: 'READONLY' },
          ]}
        />
        <Select
          value={providerFilter}
          onChange={setProviderFilter}
          style={{ width: 150 }}
          options={[
            { value: 'all', label: t('admin.users.filter_all_providers') },
            { value: 'LOCAL', label: 'LOCAL' },
            { value: 'SAML', label: 'SAML' },
            { value: 'OAUTH2', label: 'OAUTH2' },
          ]}
        />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {usersQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : usersQuery.isError ? (
          <EmptyState
            title={t('admin.users.load_error')}
            description={adminErrorMessage(usersQuery.error)}
          />
        ) : filtered.length === 0 ? (
          <EmptyState
            title={t('admin.users.title')}
            description={t('admin.users.empty')}
          />
        ) : (
          <Table<User>
            rowKey="id"
            size="middle"
            dataSource={filtered}
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: usersQuery.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
            columns={[
              {
                title: t('admin.users.col_user'),
                render: (_v, u) => {
                  const label = userDisplay(u.display_name, u.email);
                  return (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <Avatar name={label} size={28} />
                      <div>
                        <div style={{ fontSize: 13 }}>{label}</div>
                        <div className="mono muted" style={{ fontSize: 11 }}>
                          {u.email}
                        </div>
                      </div>
                    </div>
                  );
                },
              },
              {
                title: t('admin.users.col_role'),
                dataIndex: 'role',
                width: 110,
                render: (v) => <RolePill role={v} size="sm" />,
              },
              {
                title: t('admin.users.col_auth'),
                dataIndex: 'auth_provider',
                width: 110,
                render: (v) => (
                  <span className="mono" style={{ fontSize: 11 }}>
                    {v}
                  </span>
                ),
              },
              {
                title: t('admin.users.col_status'),
                dataIndex: 'active',
                width: 90,
                render: (v) =>
                  v ? (
                    <Pill
                      fg="var(--risk-low)"
                      bg="var(--risk-low-bg)"
                      border="var(--risk-low-border)"
                      withDot
                      size="sm"
                    >
                      {t('admin.users.status_active')}
                    </Pill>
                  ) : (
                    <Pill
                      fg="var(--fg-muted)"
                      bg="var(--status-neutral-bg)"
                      border="var(--status-neutral-border)"
                      withDot
                      size="sm"
                    >
                      {t('admin.users.status_inactive')}
                    </Pill>
                  ),
              },
              {
                title: t('admin.users.col_last_login'),
                dataIndex: 'last_login_at',
                width: 140,
                render: (v: string | null) =>
                  v ? <span className="muted">{timeAgo(v)}</span> : <span className="muted">—</span>,
              },
              {
                title: t('admin.users.col_created'),
                dataIndex: 'created_at',
                width: 140,
                render: (v) => <span className="muted">{fmtDate(v).split(',')[0]}</span>,
              },
              {
                title: t('admin.users.col_actions'),
                width: 60,
                render: (_v, u) => (
                  <Dropdown
                    menu={{
                      items: [
                        {
                          key: 'edit',
                          icon: <EditOutlined />,
                          label: t('common.edit'),
                          onClick: () => setEditing(u),
                        },
                        {
                          key: 'deactivate',
                          icon: <DeleteOutlined />,
                          danger: true,
                          label: t('admin.users.deactivate_action'),
                          disabled: !u.active,
                          onClick: () => onConfirmDeactivate(u),
                        },
                      ],
                    }}
                    trigger={['click']}
                  >
                    <Button size="small" type="text" icon={<MoreOutlined />} aria-label={t('common.edit')} />
                  </Dropdown>
                ),
              },
            ]}
          />
        )}
      </div>

      <PendingInvitationsSection
        invitations={invitationsQuery.data?.content ?? []}
        loading={invitationsQuery.isLoading}
        onResend={(id) => resendMutation.mutate(id)}
        onRevoke={(invitation) =>
          modal.confirm({
            title: t('admin.users.invite_revoke_confirm_title'),
            content: t('admin.users.invite_revoke_confirm_body', { email: invitation.email }),
            okType: 'danger',
            okText: t('admin.users.invite_revoke'),
            cancelText: t('common.cancel'),
            onOk: () => revokeMutation.mutateAsync(invitation.id),
          })
        }
      />

      <InviteUserModal
        open={inviting}
        onClose={() => setInviting(false)}
        onSubmit={(values) => createMutation.mutate(values)}
        loading={createMutation.isPending}
      />

      <InviteByEmailModal
        open={invitingByEmail}
        onClose={() => setInvitingByEmail(false)}
        onSubmit={(values) => inviteMutation.mutate(values)}
        loading={inviteMutation.isPending}
      />

      <EditUserModal
        user={editing}
        onClose={() => setEditing(null)}
        onSubmit={(values) =>
          editing &&
          updateMutation.mutate({
            id: editing.id,
            payload: values,
          })
        }
        loading={updateMutation.isPending}
      />
    </div>
  );
}

function InviteUserModal({
  open,
  onClose,
  onSubmit,
  loading,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (values: CreateUserInput) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<InviteFormValues>();
  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  return (
    <Modal
      open={open}
      title={t('admin.users.invite_modal_title')}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('admin.users.save_invite')}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
    >
      <Form<InviteFormValues>
        form={form}
        layout="vertical"
        initialValues={{ role: 'ANALYST' }}
        onFinish={(values) =>
          onSubmit({
            email: values.email.trim(),
            password: values.password,
            display_name: values.display_name?.trim() || null,
            role: values.role,
          })
        }
      >
        <Form.Item
          name="email"
          label={t('admin.users.label_email')}
          rules={[
            { required: true, type: 'email', max: 255 },
          ]}
        >
          <Input maxLength={255} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="password"
          label={t('admin.users.label_password')}
          extra={t('admin.users.password_help')}
          rules={[{ required: true, min: 8, max: 128 }]}
        >
          <Input.Password maxLength={128} autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="display_name"
          label={t('admin.users.label_display_name')}
          rules={[{ max: 255 }]}
        >
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item name="role" label={t('admin.users.label_role')} rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'ADMIN', label: 'ADMIN' },
              { value: 'REVIEWER', label: 'REVIEWER' },
              { value: 'ANALYST', label: 'ANALYST' },
              { value: 'READONLY', label: 'READONLY' },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function EditUserModal({
  user,
  onClose,
  onSubmit,
  loading,
}: {
  user: User | null;
  onClose: () => void;
  onSubmit: (values: UpdateUserInput) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<EditFormValues>();
  useEffect(() => {
    if (user) {
      form.resetFields();
      form.setFieldsValue({
        role: user.role,
        active: user.active,
        display_name: user.display_name,
      });
    }
  }, [user, form]);

  return (
    <Modal
      open={!!user}
      title={user ? t('admin.users.edit_modal_title', { email: user.email }) : ''}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('admin.users.save_update')}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
    >
      <Form<EditFormValues>
        form={form}
        layout="vertical"
        onFinish={(values) =>
          onSubmit({
            role: values.role,
            active: values.active,
            display_name: values.display_name?.trim() || null,
          })
        }
      >
        <Form.Item
          name="display_name"
          label={t('admin.users.label_display_name')}
          rules={[{ max: 255 }]}
        >
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item name="role" label={t('admin.users.label_role')} rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'ADMIN', label: 'ADMIN' },
              { value: 'REVIEWER', label: 'REVIEWER' },
              { value: 'ANALYST', label: 'ANALYST' },
              { value: 'READONLY', label: 'READONLY' },
            ]}
          />
        </Form.Item>
        <Form.Item name="active" label={t('admin.users.label_active')} valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function InviteByEmailModal({
  open,
  onClose,
  onSubmit,
  loading,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (values: InviteUserInput) => void;
  loading: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<InviteByEmailFormValues>();
  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  return (
    <Modal
      open={open}
      title={t('admin.users.invite_email_modal_title')}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('admin.users.invite_email_submit')}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
      destroyOnHidden
    >
      <Form<InviteByEmailFormValues>
        form={form}
        layout="vertical"
        initialValues={{ role: 'ANALYST' }}
        onFinish={(values) =>
          onSubmit({
            email: values.email.trim(),
            display_name: values.display_name?.trim() || null,
            role: values.role,
          })
        }
      >
        <Form.Item
          name="email"
          label={t('admin.users.label_email')}
          rules={[
            { required: true, message: t('validation.invite.email_required') },
            { type: 'email', message: t('validation.email_invalid') },
            { max: 255, message: t('validation.field_max_255') },
          ]}
        >
          <Input maxLength={255} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="display_name"
          label={t('admin.users.label_display_name')}
          rules={[{ max: 255, message: t('validation.field_max_255') }]}
        >
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item
          name="role"
          label={t('admin.users.label_role')}
          rules={[{ required: true, message: t('validation.invite.role_required') }]}
        >
          <Select
            options={[
              { value: 'ADMIN', label: 'ADMIN' },
              { value: 'REVIEWER', label: 'REVIEWER' },
              { value: 'ANALYST', label: 'ANALYST' },
              { value: 'READONLY', label: 'READONLY' },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function PendingInvitationsSection({
  invitations,
  loading,
  onResend,
  onRevoke,
}: {
  invitations: UserInvitation[];
  loading: boolean;
  onResend: (id: string) => void;
  onRevoke: (invitation: UserInvitation) => void;
}) {
  const { t } = useTranslation();
  if (loading) {
    return <Skeleton active paragraph={{ rows: 2 }} style={{ padding: 24 }} />;
  }
  if (invitations.length === 0) {
    return null;
  }
  return (
    <div style={{ padding: '12px 28px 24px' }}>
      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>
        {t('admin.users.invitations_title')}
      </div>
      <Table<UserInvitation>
        rowKey="id"
        size="small"
        dataSource={invitations}
        pagination={false}
        columns={[
          {
            title: t('admin.users.label_email'),
            dataIndex: 'email',
          },
          {
            title: t('admin.users.label_role'),
            dataIndex: 'role',
            render: (role: Role) => <RolePill role={role} />,
          },
          {
            title: t('admin.users.invite_status'),
            dataIndex: 'status',
            render: (status: UserInvitation['status']) => <Pill>{status}</Pill>,
          },
          {
            title: t('admin.users.invite_expires_at'),
            dataIndex: 'expires_at',
            render: (expiresAt: string) => fmtDate(expiresAt),
          },
          {
            title: '',
            key: 'actions',
            width: 80,
            render: (_, invitation) => (
              <Dropdown
                menu={{
                  items: [
                    {
                      key: 'resend',
                      label: t('admin.users.invite_resend'),
                      disabled: invitation.status === 'ACCEPTED' || invitation.status === 'REVOKED',
                      onClick: () => onResend(invitation.id),
                    },
                    {
                      key: 'revoke',
                      danger: true,
                      label: t('admin.users.invite_revoke'),
                      disabled: invitation.status === 'ACCEPTED' || invitation.status === 'REVOKED',
                      onClick: () => onRevoke(invitation),
                    },
                  ],
                }}
                trigger={['click']}
              >
                <Button
                  size="small"
                  type="text"
                  icon={<MoreOutlined />}
                  aria-label={t('common.edit')}
                />
              </Dropdown>
            ),
          },
        ]}
      />
    </div>
  );
}
