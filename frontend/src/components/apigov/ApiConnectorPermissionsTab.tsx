import { useEffect, useMemo, useState } from 'react';
import { App, Button, DatePicker, Form, Modal, Segmented, Select, Switch, Table, Tag } from 'antd';
import { TeamOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  apiConnectorKeys,
  grantApiConnectorGroupPermission,
  grantApiConnectorPermission,
  listApiConnectorGroupPermissions,
  listApiConnectorPermissions,
  listApiOperations,
  revokeApiConnectorGroupPermission,
  revokeApiConnectorPermission,
  updateApiConnectorPermission,
} from '@/api/apiConnectors';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import type {
  ApiConnectorGroupPermission,
  ApiConnectorPermission,
  ApiOperation,
  User,
} from '@/types/api';

type GrantTarget = 'user' | 'group';

interface PermissionCapabilityValues {
  can_read: boolean;
  can_write: boolean;
  can_break_glass: boolean;
  expires_at?: Dayjs | null;
  allowed_operations?: string[];
  restricted_response_fields?: string[];
}

/** Capability fields shared by the grant form and the edit modal. */
function PermissionCapabilityFields({ operations }: { operations: ApiOperation[] }) {
  const { t } = useTranslation();
  return (
    <>
      <Form.Item name="can_read" label={t('apiGov.settings.canRead')} valuePropName="checked">
        <Switch size="small" />
      </Form.Item>
      <Form.Item name="can_write" label={t('apiGov.settings.canWrite')} valuePropName="checked">
        <Switch size="small" />
      </Form.Item>
      <Form.Item name="can_break_glass" label={t('apiGov.settings.canBreakGlass')} valuePropName="checked">
        <Switch size="small" />
      </Form.Item>
      <Form.Item name="expires_at" label={t('apiGov.settings.expiresAt')}>
        <DatePicker showTime style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="allowed_operations" label={t('apiGov.settings.allowedOperations')}>
        <Select<string[]>
          mode="multiple"
          allowClear
          optionFilterProp="label"
          placeholder={t('apiGov.settings.allowedOperationsPlaceholder')}
          options={operations.map((op) => ({
            value: op.operation_id,
            label: `${op.verb} ${op.path}`,
          }))}
        />
      </Form.Item>
      <Form.Item name="restricted_response_fields" label={t('apiGov.settings.restrictedResponseFields')}>
        <Select<string[]>
          mode="tags"
          allowClear
          tokenSeparators={[',']}
          placeholder={t('apiGov.settings.restrictedResponseFieldsPlaceholder')}
        />
      </Form.Item>
    </>
  );
}

function EditPermissionModal({
  connectorId,
  permission,
  operations,
  onClose,
}: {
  connectorId: string;
  permission: ApiConnectorPermission | null;
  operations: ApiOperation[];
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<PermissionCapabilityValues>();

  useEffect(() => {
    if (!permission) return;
    form.setFieldsValue({
      can_read: permission.can_read,
      can_write: permission.can_write,
      can_break_glass: permission.can_break_glass,
      expires_at: permission.expires_at ? dayjs(permission.expires_at) : null,
      allowed_operations: permission.allowed_operations,
      restricted_response_fields: permission.restricted_response_fields,
    });
  }, [permission, form]);

  const updateMutation = useMutation({
    mutationFn: (values: PermissionCapabilityValues) =>
      updateApiConnectorPermission(connectorId, permission!.id, {
        can_read: values.can_read,
        can_write: values.can_write,
        can_break_glass: values.can_break_glass,
        expires_at: values.expires_at ? values.expires_at.toISOString() : null,
        allowed_operations: values.allowed_operations ?? [],
        restricted_response_fields: values.restricted_response_fields ?? [],
      }),
    onSuccess: () => {
      message.success(t('apiGov.settings.updated'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.permissions(connectorId) });
      onClose();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const subject = permission
    ? permission.user_display_name
      ? `${permission.user_display_name} (${permission.user_email ?? permission.user_id})`
      : permission.user_email ?? permission.user_id
    : '';

  return (
    <Modal
      open={!!permission}
      title={t('apiGov.settings.editPermission', { user: subject })}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('apiGov.settings.save')}
      confirmLoading={updateMutation.isPending}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" onFinish={(values) => updateMutation.mutate(values)}>
        <PermissionCapabilityFields operations={operations} />
      </Form>
    </Modal>
  );
}

type GrantFormValues = PermissionCapabilityValues & {
  target: GrantTarget;
  user_id?: string;
  group_id?: string;
};

export function ApiConnectorPermissionsTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<GrantFormValues>();
  const target: GrantTarget = Form.useWatch('target', form) ?? 'user';
  const [editing, setEditing] = useState<ApiConnectorPermission | null>(null);
  const permsQuery = useQuery({
    queryKey: apiConnectorKeys.permissions(connectorId),
    queryFn: () => listApiConnectorPermissions(connectorId),
  });
  const groupPermsQuery = useQuery({
    queryKey: apiConnectorKeys.groupPermissions(connectorId),
    queryFn: () => listApiConnectorGroupPermissions(connectorId),
  });
  const operationsQuery = useQuery({
    queryKey: apiConnectorKeys.operations(connectorId),
    queryFn: () => listApiOperations(connectorId),
  });
  const operations = operationsQuery.data ?? [];
  const usersQuery = useQuery({
    queryKey: userKeys.list({ size: 100 }),
    queryFn: () => listUsers({ size: 100 }),
  });
  const groupsQuery = useQuery({
    queryKey: groupKeys.lists(),
    queryFn: () => listAllGroups(),
  });
  const taken = useMemo(
    () => new Set((permsQuery.data ?? []).map((p) => p.user_id)),
    [permsQuery.data],
  );
  const eligible: User[] = useMemo(
    () => (usersQuery.data?.content ?? []).filter((u) => u.active && !taken.has(u.id)),
    [usersQuery.data, taken],
  );
  const takenGroups = useMemo(
    () => new Set((groupPermsQuery.data ?? []).map((p) => p.group_id)),
    [groupPermsQuery.data],
  );
  const eligibleGroups = useMemo(
    () => (groupsQuery.data ?? []).filter((g) => !takenGroups.has(g.id)),
    [groupsQuery.data, takenGroups],
  );
  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.permissions(connectorId) });
  const invalidateGroups = () =>
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.groupPermissions(connectorId) });
  const grantMutation = useMutation<
    ApiConnectorPermission | ApiConnectorGroupPermission,
    Error,
    GrantFormValues
  >({
    mutationFn: (values: GrantFormValues) => {
      const shared = {
        can_read: values.can_read,
        can_write: values.can_write,
        can_break_glass: values.can_break_glass,
        expires_at: values.expires_at ? values.expires_at.toISOString() : null,
        allowed_operations: values.allowed_operations ?? [],
        restricted_response_fields: values.restricted_response_fields ?? [],
      };
      if (values.target === 'group') {
        return grantApiConnectorGroupPermission(connectorId, {
          group_id: values.group_id!,
          ...shared,
        });
      }
      return grantApiConnectorPermission(connectorId, { user_id: values.user_id!, ...shared });
    },
    onSuccess: (_data, values) => {
      message.success(t('apiGov.settings.granted'));
      form.resetFields();
      if (values.target === 'group') invalidateGroups();
      else invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  const revokeMutation = useMutation({
    mutationFn: (permissionId: string) => revokeApiConnectorPermission(connectorId, permissionId),
    onSuccess: () => {
      message.success(t('apiGov.settings.revoked'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  const revokeGroupMutation = useMutation({
    mutationFn: (permissionId: string) =>
      revokeApiConnectorGroupPermission(connectorId, permissionId),
    onSuccess: () => {
      message.success(t('apiGov.settings.revoked'));
      invalidateGroups();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  const groupPerms = groupPermsQuery.data ?? [];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 760 }}>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ target: 'user', can_read: true, can_write: false, can_break_glass: false }}
        onFinish={(values) => grantMutation.mutate(values)}
      >
        <Form.Item name="target" label={t('apiGov.settings.grantTarget')}>
          <Segmented<GrantTarget>
            options={[
              { value: 'user', label: t('apiGov.settings.grantTargetUser') },
              { value: 'group', label: t('apiGov.settings.grantTargetGroup') },
            ]}
          />
        </Form.Item>
        {target === 'group' ? (
          <Form.Item
            name="group_id"
            label={t('apiGov.settings.group')}
            rules={[{ required: true, message: t('apiGov.settings.groupRequired') }]}
          >
            <Select<string>
              showSearch
              optionFilterProp="label"
              placeholder={t('apiGov.settings.groupPlaceholder')}
              style={{ maxWidth: 360 }}
              loading={groupsQuery.isLoading}
              notFoundContent={
                groupsQuery.isLoading
                  ? t('apiGov.settings.userLoading')
                  : t('apiGov.settings.groupEmpty')
              }
              options={eligibleGroups.map((g) => ({ value: g.id, label: g.name }))}
            />
          </Form.Item>
        ) : (
          <Form.Item
            name="user_id"
            label={t('apiGov.settings.user')}
            rules={[{ required: true, message: t('apiGov.settings.userRequired') }]}
          >
            <Select<string>
              showSearch
              optionFilterProp="label"
              placeholder={t('apiGov.settings.userPlaceholder')}
              style={{ maxWidth: 360 }}
              loading={usersQuery.isLoading}
              notFoundContent={
                usersQuery.isLoading
                  ? t('apiGov.settings.userLoading')
                  : t('apiGov.settings.userEmpty')
              }
              options={eligible.map((u) => ({
                value: u.id,
                label: u.display_name ? `${u.display_name} (${u.email})` : u.email,
              }))}
            />
          </Form.Item>
        )}
        <PermissionCapabilityFields operations={operations} />
        <Button type="primary" htmlType="submit" loading={grantMutation.isPending}>
          {t('apiGov.settings.grant')}
        </Button>
      </Form>
      <Table<ApiConnectorPermission>
        rowKey="id"
        size="small"
        pagination={false}
        loading={permsQuery.isLoading}
        dataSource={permsQuery.data ?? []}
        locale={{ emptyText: t('apiGov.settings.noPermissions') }}
        columns={[
          { title: t('apiGov.settings.user'), dataIndex: 'user_email', render: (e: string | null, r: ApiConnectorPermission) => e ?? r.user_id },
          { title: t('apiGov.settings.canRead'), dataIndex: 'can_read', render: (v: boolean) => (v ? '✓' : '—') },
          { title: t('apiGov.settings.canWrite'), dataIndex: 'can_write', render: (v: boolean) => (v ? '✓' : '—') },
          { title: t('apiGov.settings.canBreakGlass'), dataIndex: 'can_break_glass', render: (v: boolean) => (v ? '✓' : '—') },
          { title: t('apiGov.settings.expiresAt'), dataIndex: 'expires_at', render: (v: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—') },
          { title: t('apiGov.settings.allowedOperations'), dataIndex: 'allowed_operations', render: (v: string[]) => (v.length ? v.length : t('apiGov.settings.allOperations')) },
          { title: t('apiGov.settings.restrictedResponseFields'), dataIndex: 'restricted_response_fields', render: (v: string[]) => (v.length ? v.length : '—') },
          { title: '', key: 'a', render: (_: unknown, row: ApiConnectorPermission) => (
            <div style={{ display: 'flex', gap: 8 }}>
              <Button size="small" onClick={() => setEditing(row)}>
                {t('apiGov.settings.edit')}
              </Button>
              <Button size="small" danger loading={revokeMutation.isPending} onClick={() => revokeMutation.mutate(row.id)}>
                {t('apiGov.settings.revoke')}
              </Button>
            </div>
          ) },
        ]}
      />
      {(groupPerms.length > 0 || groupPermsQuery.isLoading) && (
        <Table<ApiConnectorGroupPermission>
          rowKey="id"
          size="small"
          pagination={false}
          loading={groupPermsQuery.isLoading}
          dataSource={groupPerms}
          title={() => t('apiGov.settings.groupPermissionsTitle')}
          columns={[
            {
              title: t('apiGov.settings.group'),
              dataIndex: 'group_name',
              render: (name: string, r: ApiConnectorGroupPermission) => (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                  <Tag icon={<TeamOutlined />} color="blue">
                    {t('apiGov.settings.groupTag')}
                  </Tag>
                  {name}
                  <span className="muted" style={{ fontSize: 11 }}>
                    {t('apiGov.settings.memberCount', { count: r.member_count })}
                  </span>
                </span>
              ),
            },
            { title: t('apiGov.settings.canRead'), dataIndex: 'can_read', render: (v: boolean) => (v ? '✓' : '—') },
            { title: t('apiGov.settings.canWrite'), dataIndex: 'can_write', render: (v: boolean) => (v ? '✓' : '—') },
            { title: t('apiGov.settings.canBreakGlass'), dataIndex: 'can_break_glass', render: (v: boolean) => (v ? '✓' : '—') },
            { title: t('apiGov.settings.expiresAt'), dataIndex: 'expires_at', render: (v: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—') },
            { title: t('apiGov.settings.allowedOperations'), dataIndex: 'allowed_operations', render: (v: string[]) => (v.length ? v.length : t('apiGov.settings.allOperations')) },
            { title: t('apiGov.settings.restrictedResponseFields'), dataIndex: 'restricted_response_fields', render: (v: string[]) => (v.length ? v.length : '—') },
            { title: '', key: 'a', render: (_: unknown, row: ApiConnectorGroupPermission) => (
              <Button size="small" danger loading={revokeGroupMutation.isPending} onClick={() => revokeGroupMutation.mutate(row.id)}>
                {t('apiGov.settings.revoke')}
              </Button>
            ) },
          ]}
        />
      )}
      <EditPermissionModal
        connectorId={connectorId}
        permission={editing}
        operations={operations}
        onClose={() => setEditing(null)}
      />
    </div>
  );
}
