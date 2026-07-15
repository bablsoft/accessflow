import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Checkbox,
  Drawer,
  Form,
  Input,
  Skeleton,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createRole,
  deleteRole,
  getPermissionCatalog,
  listRoles,
  permissionCatalogKeys,
  roleKeys,
  updateRole,
} from '@/api/roles';
import { rolesErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { roleDisplayName } from '@/utils/roleOptions';
import type {
  CreateRoleInput,
  PermissionCatalogGroup,
  RoleSummary,
} from '@/types/api';

const permissionLabel = (t: TFunction, name: string): string =>
  t(`enums.permission.${name}`, { defaultValue: name });

const permissionGroupLabel = (t: TFunction, group: string): string =>
  t(`enums.permission_group.${group}`, { defaultValue: group });

interface RoleFormValues {
  name: string;
  description?: string;
  permissions: string[];
}

type DrawerState =
  | { mode: 'create' }
  | { mode: 'edit'; role: RoleSummary }
  | { mode: 'view'; role: RoleSummary }
  | null;

export function RolesPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [drawer, setDrawer] = useState<DrawerState>(null);

  const rolesQuery = useQuery({
    queryKey: roleKeys.lists(),
    queryFn: listRoles,
  });

  const createMutation = useMutation({
    mutationFn: (input: CreateRoleInput) => createRole(input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: roleKeys.all });
      message.success(t('admin.roles.create_success'));
      setDrawer(null);
    },
    onError: (err) => showApiError(message, err, rolesErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: (vars: { id: string; input: CreateRoleInput }) =>
      updateRole(vars.id, vars.input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: roleKeys.all });
      message.success(t('admin.roles.update_success'));
      setDrawer(null);
    },
    onError: (err) => showApiError(message, err, rolesErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteRole(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: roleKeys.all });
      message.success(t('admin.roles.delete_success'));
    },
    onError: (err) => showApiError(message, err, rolesErrorMessage),
  });

  const onConfirmDelete = (role: RoleSummary) =>
    modal.confirm({
      title: t('admin.roles.delete_confirm_title'),
      content: t('admin.roles.delete_confirm_body', { name: role.name }),
      okType: 'danger',
      okText: t('admin.roles.delete_action'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(role.id),
    });

  const roles = rolesQuery.data ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.roles.title')}
        subtitle={t('admin.roles.subtitle_count', { count: roles.length })}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => rolesQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setDrawer({ mode: 'create' })}
            >
              {t('admin.roles.create_button')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {rolesQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : rolesQuery.isError ? (
          <EmptyState
            title={t('admin.roles.load_error')}
            description={rolesErrorMessage(rolesQuery.error)}
          />
        ) : roles.length === 0 ? (
          <EmptyState title={t('admin.roles.title')} description={t('admin.roles.empty')} />
        ) : (
          <Table<RoleSummary>
            rowKey="id"
            size="middle"
            dataSource={roles}
            scroll={{ x: 'max-content' }}
            pagination={false}
            columns={[
              {
                title: t('admin.roles.col_name'),
                key: 'name',
                render: (_v, role) => (
                  <Space size={8}>
                    <span style={{ fontSize: 13 }}>{roleDisplayName(t, role)}</span>
                    {role.system && <Tag>{t('admin.roles.system_tag')}</Tag>}
                  </Space>
                ),
              },
              {
                title: t('admin.roles.col_description'),
                dataIndex: 'description',
                render: (v: string | null) =>
                  v ? (
                    <span className="muted" style={{ fontSize: 12 }}>{v}</span>
                  ) : (
                    <span className="muted">—</span>
                  ),
              },
              {
                title: t('admin.roles.col_permissions'),
                key: 'permissions',
                width: 140,
                render: (_v, role) => (
                  <span className="mono" style={{ fontSize: 12 }}>
                    {t('admin.roles.permissions_count', { count: role.permissions.length })}
                  </span>
                ),
              },
              {
                title: t('admin.roles.col_users'),
                dataIndex: 'assigned_user_count',
                width: 140,
                render: (v: number) => (
                  <span className="mono" style={{ fontSize: 12 }}>
                    {t('admin.roles.users_count', { count: v })}
                  </span>
                ),
              },
              {
                title: t('admin.roles.col_actions'),
                key: 'actions',
                width: 130,
                render: (_v, role) => (
                  <Space size={4}>
                    <Button
                      size="small"
                      type="text"
                      icon={<EyeOutlined />}
                      aria-label={t('admin.roles.view_action')}
                      onClick={() => setDrawer({ mode: 'view', role })}
                    />
                    <Tooltip
                      title={role.system ? t('admin.roles.system_immutable_tooltip') : ''}
                    >
                      <Button
                        size="small"
                        type="text"
                        icon={<EditOutlined />}
                        aria-label={t('common.edit')}
                        disabled={role.system}
                        onClick={() => setDrawer({ mode: 'edit', role })}
                      />
                    </Tooltip>
                    <Tooltip
                      title={role.system ? t('admin.roles.system_immutable_tooltip') : ''}
                    >
                      <Button
                        size="small"
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        aria-label={t('admin.roles.delete_action')}
                        disabled={role.system}
                        onClick={() => onConfirmDelete(role)}
                      />
                    </Tooltip>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </div>

      <RoleDrawer
        state={drawer}
        onClose={() => setDrawer(null)}
        saving={createMutation.isPending || updateMutation.isPending}
        onSubmit={(values) => {
          const input: CreateRoleInput = {
            name: values.name.trim(),
            description: values.description?.trim() || null,
            permissions: values.permissions,
          };
          if (drawer?.mode === 'edit') {
            updateMutation.mutate({ id: drawer.role.id, input });
          } else {
            createMutation.mutate(input);
          }
        }}
      />
    </div>
  );
}

function RoleDrawer({
  state,
  onClose,
  onSubmit,
  saving,
}: {
  state: DrawerState;
  onClose: () => void;
  onSubmit: (values: RoleFormValues) => void;
  saving: boolean;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<RoleFormValues>();
  const open = state !== null;
  const readOnly = state?.mode === 'view';
  const editing = state?.mode === 'edit' || state?.mode === 'view' ? state.role : null;

  const catalogQuery = useQuery({
    queryKey: permissionCatalogKeys.catalog(),
    queryFn: getPermissionCatalog,
    enabled: open,
    staleTime: 5 * 60_000,
  });
  const groups = catalogQuery.data?.groups ?? [];

  useEffect(() => {
    if (!open) return;
    if (editing) {
      form.setFieldsValue({
        name: editing.name,
        description: editing.description ?? undefined,
        permissions: editing.permissions,
      });
    } else {
      form.resetFields();
    }
  }, [open, editing, form]);

  const title =
    state?.mode === 'view'
      ? t('admin.roles.view_title', { name: editing?.name ?? '' })
      : state?.mode === 'edit'
        ? t('admin.roles.edit_title', { name: editing?.name ?? '' })
        : t('admin.roles.create_title');

  return (
    <Drawer
      open={open}
      title={title}
      onClose={onClose}
      width={520}
      destroyOnHidden
      footer={
        readOnly ? null : (
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="primary" loading={saving} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </div>
        )
      }
    >
      <Form<RoleFormValues>
        form={form}
        layout="vertical"
        disabled={readOnly}
        initialValues={{ permissions: [] }}
        onFinish={onSubmit}
      >
        <Form.Item
          name="name"
          label={t('admin.roles.label_name')}
          rules={[
            { required: true, message: t('admin.roles.validation_name_required') },
            { max: 100, message: t('admin.roles.validation_name_max') },
          ]}
        >
          <Input maxLength={100} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="description"
          label={t('admin.roles.label_description')}
          rules={[{ max: 500, message: t('admin.roles.validation_description_max') }]}
        >
          <Input.TextArea maxLength={500} rows={2} />
        </Form.Item>
        <Form.Item
          name="permissions"
          label={t('admin.roles.label_permissions')}
          rules={[
            {
              validator: async (_rule, value: string[] | undefined) => {
                if (!value || value.length === 0) {
                  throw new Error(t('admin.roles.validation_permissions_required'));
                }
              },
            },
          ]}
        >
          <GroupedPermissionPicker
            groups={groups}
            loading={catalogQuery.isLoading}
            disabled={readOnly}
          />
        </Form.Item>
      </Form>
    </Drawer>
  );
}

function GroupedPermissionPicker({
  value,
  onChange,
  groups,
  loading,
  disabled,
}: {
  value?: string[];
  onChange?: (next: string[]) => void;
  groups: PermissionCatalogGroup[];
  loading: boolean;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const selected = value ?? [];

  if (loading) {
    return <Skeleton active paragraph={{ rows: 4 }} />;
  }

  const onGroupChange = (group: PermissionCatalogGroup, groupValues: string[]) => {
    const outside = selected.filter((p) => !group.permissions.includes(p));
    onChange?.([...outside, ...groupValues]);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {groups.map((group) => (
        <div key={group.group}>
          <div
            className="mono muted"
            style={{ fontSize: 11, textTransform: 'uppercase', marginBottom: 4 }}
          >
            {permissionGroupLabel(t, group.group)}
          </div>
          <Checkbox.Group
            style={{ display: 'flex', flexDirection: 'column', gap: 4 }}
            options={group.permissions.map((p) => ({
              value: p,
              label: permissionLabel(t, p),
            }))}
            value={selected.filter((p) => group.permissions.includes(p))}
            onChange={(vals) => onGroupChange(group, vals as string[])}
            disabled={disabled}
          />
        </div>
      ))}
    </div>
  );
}
