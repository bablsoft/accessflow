import { useEffect, useMemo, useState } from 'react';
import { App, Button, DatePicker, Form, Modal, Popconfirm, Segmented, Select, Switch, Table, Tag } from 'antd';
import { TeamOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  deploymentPipelineKeys,
  grantDeploymentGroupPermission,
  grantDeploymentPermission,
  listDeploymentGroupPermissions,
  listDeploymentPermissions,
  revokeDeploymentGroupPermission,
  revokeDeploymentPermission,
  updateDeploymentGroupPermission,
  updateDeploymentPermission,
} from '@/api/deploymentPipelines';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import { fmtDate } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  DeploymentPipelineGroupPermission,
  DeploymentPipelinePermission,
  User,
} from '@/types/api';

type GrantTarget = 'user' | 'group';

interface CapabilityValues {
  can_trigger: boolean;
  can_break_glass: boolean;
  expires_at?: Dayjs | null;
}

/** Capability fields shared by the grant form and the edit modal. */
function CapabilityFields() {
  const { t } = useTranslation();
  return (
    <>
      <Form.Item
        name="can_trigger"
        label={t('deploygov.settings.canTrigger')}
        valuePropName="checked"
      >
        <Switch size="small" />
      </Form.Item>
      <Form.Item
        name="can_break_glass"
        label={t('deploygov.settings.canBreakGlass')}
        valuePropName="checked"
      >
        <Switch size="small" />
      </Form.Item>
      <Form.Item name="expires_at" label={t('deploygov.settings.expiresAt')}>
        <DatePicker showTime style={{ width: '100%' }} />
      </Form.Item>
    </>
  );
}

type EditTarget =
  | { kind: 'user'; permission: DeploymentPipelinePermission }
  | { kind: 'group'; permission: DeploymentPipelineGroupPermission };

function EditPermissionModal({
  pipelineId,
  target,
  onClose,
}: {
  pipelineId: string;
  target: EditTarget | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<CapabilityValues>();

  useEffect(() => {
    if (!target) return;
    const { permission } = target;
    form.setFieldsValue({
      can_trigger: permission.can_trigger,
      can_break_glass: permission.can_break_glass,
      expires_at: permission.expires_at ? dayjs(permission.expires_at) : null,
    });
  }, [target, form]);

  const updateMutation = useMutation<
    DeploymentPipelinePermission | DeploymentPipelineGroupPermission,
    Error,
    CapabilityValues
  >({
    mutationFn: (values: CapabilityValues) => {
      const payload = {
        can_trigger: values.can_trigger,
        can_break_glass: values.can_break_glass,
        expires_at: values.expires_at ? values.expires_at.toISOString() : null,
      };
      if (target!.kind === 'group') {
        return updateDeploymentGroupPermission(pipelineId, target!.permission.id, payload);
      }
      return updateDeploymentPermission(pipelineId, target!.permission.id, payload);
    },
    onSuccess: () => {
      message.success(t('deploygov.settings.updated'));
      void queryClient.invalidateQueries({
        queryKey:
          target!.kind === 'group'
            ? deploymentPipelineKeys.groupPermissions(pipelineId)
            : deploymentPipelineKeys.permissions(pipelineId),
      });
      onClose();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  let title = '';
  if (target?.kind === 'group') {
    title = t('deploygov.settings.editGroupPermission', { group: target.permission.group_name });
  } else if (target?.kind === 'user') {
    const p = target.permission;
    const subject = p.user_display_name
      ? `${p.user_display_name} (${p.user_email ?? p.user_id})`
      : p.user_email ?? p.user_id;
    title = t('deploygov.settings.editUserPermission', { user: subject });
  }

  return (
    <Modal
      open={!!target}
      title={title}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      confirmLoading={updateMutation.isPending}
      destroyOnHidden
    >
      {/* Named so its control ids are prefixed — the grant form below is always mounted and
          declares the same field names, which would otherwise duplicate ids and labels. */}
      <Form
        form={form}
        name="deployment_permission_edit"
        layout="vertical"
        onFinish={(values) => updateMutation.mutate(values)}
      >
        <CapabilityFields />
      </Form>
    </Modal>
  );
}

type GrantFormValues = CapabilityValues & {
  target: GrantTarget;
  user_id?: string;
  group_id?: string;
};

export function PipelinePermissionsTab({ pipelineId }: { pipelineId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<GrantFormValues>();
  const target: GrantTarget = Form.useWatch('target', form) ?? 'user';
  const [editing, setEditing] = useState<EditTarget | null>(null);

  const permsQuery = useQuery({
    queryKey: deploymentPipelineKeys.permissions(pipelineId),
    queryFn: () => listDeploymentPermissions(pipelineId),
  });
  const groupPermsQuery = useQuery({
    queryKey: deploymentPipelineKeys.groupPermissions(pipelineId),
    queryFn: () => listDeploymentGroupPermissions(pipelineId),
  });
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
    queryClient.invalidateQueries({ queryKey: deploymentPipelineKeys.permissions(pipelineId) });
  const invalidateGroups = () =>
    queryClient.invalidateQueries({
      queryKey: deploymentPipelineKeys.groupPermissions(pipelineId),
    });

  const grantMutation = useMutation<
    DeploymentPipelinePermission | DeploymentPipelineGroupPermission,
    Error,
    GrantFormValues
  >({
    mutationFn: (values: GrantFormValues) => {
      const shared = {
        can_trigger: values.can_trigger,
        can_break_glass: values.can_break_glass,
        expires_at: values.expires_at ? values.expires_at.toISOString() : null,
      };
      if (values.target === 'group') {
        return grantDeploymentGroupPermission(pipelineId, {
          group_id: values.group_id!,
          ...shared,
        });
      }
      return grantDeploymentPermission(pipelineId, { user_id: values.user_id!, ...shared });
    },
    onSuccess: (_data, values) => {
      message.success(t('deploygov.settings.granted'));
      form.resetFields();
      if (values.target === 'group') void invalidateGroups();
      else void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const revokeMutation = useMutation({
    mutationFn: (permissionId: string) => revokeDeploymentPermission(pipelineId, permissionId),
    onSuccess: () => {
      message.success(t('deploygov.settings.revoked'));
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });
  const revokeGroupMutation = useMutation({
    mutationFn: (permissionId: string) =>
      revokeDeploymentGroupPermission(pipelineId, permissionId),
    onSuccess: () => {
      message.success(t('deploygov.settings.revoked'));
      void invalidateGroups();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const groupPerms = groupPermsQuery.data ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 760 }}>
      <Form
        form={form}
        layout="vertical"
        initialValues={{ target: 'user', can_trigger: true, can_break_glass: false }}
        onFinish={(values) => grantMutation.mutate(values)}
      >
        <Form.Item name="target" label={t('deploygov.settings.grantTitle')}>
          <Segmented<GrantTarget>
            options={[
              { value: 'user', label: t('deploygov.settings.targetUser') },
              { value: 'group', label: t('deploygov.settings.targetGroup') },
            ]}
          />
        </Form.Item>
        {target === 'group' ? (
          <Form.Item
            name="group_id"
            label={t('deploygov.settings.groupLabel')}
            rules={[{ required: true, message: t('deploygov.settings.groupRequired') }]}
          >
            <Select<string>
              showSearch={{ optionFilterProp: 'label' }}
              placeholder={t('deploygov.settings.groupPlaceholder')}
              style={{ maxWidth: 360 }}
              loading={groupsQuery.isLoading}
              options={eligibleGroups.map((g) => ({ value: g.id, label: g.name }))}
            />
          </Form.Item>
        ) : (
          <Form.Item
            name="user_id"
            label={t('deploygov.settings.userLabel')}
            rules={[{ required: true, message: t('deploygov.settings.userRequired') }]}
          >
            <Select<string>
              showSearch={{ optionFilterProp: 'label' }}
              placeholder={t('deploygov.settings.userPlaceholder')}
              style={{ maxWidth: 360 }}
              loading={usersQuery.isLoading}
              options={eligible.map((u) => ({
                value: u.id,
                label: u.display_name ? `${u.display_name} (${u.email})` : u.email,
              }))}
            />
          </Form.Item>
        )}
        <CapabilityFields />
        <Button type="primary" htmlType="submit" loading={grantMutation.isPending}>
          {t('deploygov.settings.grant')}
        </Button>
      </Form>
      <Table<DeploymentPipelinePermission>
        rowKey="id"
        size="small"
        pagination={false}
        loading={permsQuery.isLoading}
        dataSource={permsQuery.data ?? []}
        title={() => t('deploygov.settings.userPermissionsTitle')}
        locale={{ emptyText: t('deploygov.settings.permissionsEmpty') }}
        columns={[
          {
            title: t('deploygov.settings.userLabel'),
            dataIndex: 'user_email',
            render: (e: string | null, r: DeploymentPipelinePermission) =>
              r.user_display_name ? `${r.user_display_name} (${e ?? r.user_id})` : e ?? r.user_id,
          },
          {
            title: t('deploygov.settings.canTrigger'),
            dataIndex: 'can_trigger',
            align: 'center' as const,
            render: (v: boolean) => (v ? '✓' : '—'),
          },
          {
            title: t('deploygov.settings.canBreakGlass'),
            dataIndex: 'can_break_glass',
            align: 'center' as const,
            render: (v: boolean) => (v ? '✓' : '—'),
          },
          {
            title: t('deploygov.settings.expiresAt'),
            dataIndex: 'expires_at',
            render: (v: string | null) =>
              v ? fmtDate(v) : t('deploygov.settings.never'),
          },
          {
            title: '',
            key: 'actions',
            render: (_: unknown, row: DeploymentPipelinePermission) => (
              <div style={{ display: 'flex', gap: 8 }}>
                <Button size="small" onClick={() => setEditing({ kind: 'user', permission: row })}>
                  {t('common.edit')}
                </Button>
                <Popconfirm
                  title={t('deploygov.settings.revokeConfirm')}
                  okText={t('deploygov.settings.revoke')}
                  cancelText={t('common.cancel')}
                  onConfirm={() => revokeMutation.mutate(row.id)}
                >
                  <Button size="small" danger>
                    {t('deploygov.settings.revoke')}
                  </Button>
                </Popconfirm>
              </div>
            ),
          },
        ]}
      />
      {(groupPerms.length > 0 || groupPermsQuery.isLoading) && (
        <Table<DeploymentPipelineGroupPermission>
          rowKey="id"
          size="small"
          pagination={false}
          loading={groupPermsQuery.isLoading}
          dataSource={groupPerms}
          title={() => t('deploygov.settings.groupPermissionsTitle')}
          columns={[
            {
              title: t('deploygov.settings.groupLabel'),
              dataIndex: 'group_name',
              render: (name: string | null, r: DeploymentPipelineGroupPermission) => (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                  <Tag icon={<TeamOutlined />} color="blue">
                    {t('deploygov.settings.targetGroup')}
                  </Tag>
                  {name ?? r.group_id}
                  <span className="muted" style={{ fontSize: 11 }}>
                    {t('deploygov.settings.memberCount', { count: r.member_count })}
                  </span>
                </span>
              ),
            },
            {
              title: t('deploygov.settings.canTrigger'),
              dataIndex: 'can_trigger',
              align: 'center' as const,
              render: (v: boolean) => (v ? '✓' : '—'),
            },
            {
              title: t('deploygov.settings.canBreakGlass'),
              dataIndex: 'can_break_glass',
              align: 'center' as const,
              render: (v: boolean) => (v ? '✓' : '—'),
            },
            {
              title: t('deploygov.settings.expiresAt'),
              dataIndex: 'expires_at',
              render: (v: string | null) =>
                v ? fmtDate(v) : t('deploygov.settings.never'),
            },
            {
              title: '',
              key: 'actions',
              render: (_: unknown, row: DeploymentPipelineGroupPermission) => (
                <div style={{ display: 'flex', gap: 8 }}>
                  <Button
                    size="small"
                    onClick={() => setEditing({ kind: 'group', permission: row })}
                  >
                    {t('common.edit')}
                  </Button>
                  <Popconfirm
                    title={t('deploygov.settings.revokeConfirm')}
                    okText={t('deploygov.settings.revoke')}
                    cancelText={t('common.cancel')}
                    onConfirm={() => revokeGroupMutation.mutate(row.id)}
                  >
                    <Button size="small" danger>
                      {t('deploygov.settings.revoke')}
                    </Button>
                  </Popconfirm>
                </div>
              ),
            },
          ]}
        />
      )}
      <EditPermissionModal pipelineId={pipelineId} target={editing} onClose={() => setEditing(null)} />
    </div>
  );
}
