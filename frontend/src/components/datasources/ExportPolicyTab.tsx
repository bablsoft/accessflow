import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Form,
  InputNumber,
  Modal,
  Select,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  MinusCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createExportPolicy,
  deleteExportPolicy,
  exportPolicyKeys,
  listExportPolicies,
  updateExportPolicy,
} from '@/api/exportPolicies';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import { listRoles, roleKeys } from '@/api/roles';
import {
  DATA_CLASSIFICATIONS,
  dataClassificationLabel,
  enumOptions,
  EXPORT_POLICY_MODES,
  exportPolicyModeLabel,
} from '@/utils/enumLabels';
import { roleSelectOptions } from '@/utils/roleOptions';
import { watermarkFooter, watermarkHeader } from '@/utils/watermarkPreview';
import { userDisplay } from '@/utils/userDisplay';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { useAuthStore } from '@/store/authStore';
import type {
  CreateExportPolicyInput,
  DataClassification,
  ExportPolicy,
  ExportPolicyMode,
  User,
} from '@/types/api';

export function ExportPolicyTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ExportPolicy | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const policiesQuery = useQuery({
    queryKey: exportPolicyKeys.list(dsId),
    queryFn: () => listExportPolicies(dsId),
  });
  const policies = policiesQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (policyId: string) => deleteExportPolicy(dsId, policyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: exportPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.export_policies.delete_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('datasources.settings.export_policies.delete_error')),
      );
    },
  });

  const onAdd = () => {
    setEditing(null);
    setModalOpen(true);
  };

  const onEdit = (policy: ExportPolicy) => {
    setEditing(policy);
    setModalOpen(true);
  };

  const onDelete = (policy: ExportPolicy) => {
    modal.confirm({
      title: t('datasources.settings.export_policies.delete_confirm_title'),
      content: t('datasources.settings.export_policies.delete_confirm_body'),
      okType: 'danger',
      okText: t('datasources.settings.export_policies.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(policy.id),
    });
  };

  return (
    <div style={{ padding: 28 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: 16,
          gap: 16,
        }}
      >
        <div>
          <div style={{ fontWeight: 600 }}>
            {t('datasources.settings.export_policies.title')}
          </div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('datasources.settings.export_policies.description')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>
          {t('datasources.settings.export_policies.add')}
        </Button>
      </div>

      {!policiesQuery.isLoading && policies.length === 0 ? (
        <EmptyState
          title={t('datasources.settings.export_policies.empty_title')}
          description={t('datasources.settings.export_policies.empty_description')}
        />
      ) : (
        <Table<ExportPolicy>
          rowKey="id"
          size="middle"
          loading={policiesQuery.isLoading}
          dataSource={policies}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('datasources.settings.export_policies.col_mode'),
              dataIndex: 'mode',
              width: 180,
              render: (v: ExportPolicyMode) => <Tag>{exportPolicyModeLabel(t, v)}</Tag>,
            },
            {
              title: t('datasources.settings.export_policies.col_details'),
              render: (_v, p) => <PolicyDetails policy={p} />,
            },
            {
              title: t('datasources.settings.export_policies.col_applies_to'),
              render: (_v, p) => <AppliesToSummary policy={p} />,
            },
            {
              title: t('datasources.settings.export_policies.col_enabled'),
              width: 90,
              align: 'center',
              render: (_v, p) => {
                const label = p.enabled
                  ? t('datasources.settings.export_policies.label_enabled')
                  : t('datasources.settings.export_policies.state_disabled');
                return (
                  <Tooltip title={label}>
                    {p.enabled ? (
                      <CheckCircleOutlined
                        aria-label={label}
                        style={{ color: 'var(--risk-low)' }}
                      />
                    ) : (
                      <MinusCircleOutlined aria-label={label} className="muted" />
                    )}
                  </Tooltip>
                );
              },
            },
            {
              title: t('datasources.settings.export_policies.col_actions'),
              width: 120,
              align: 'right',
              render: (_v, p) => (
                <>
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={t('datasources.settings.export_policies.edit')}
                    onClick={() => onEdit(p)}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('datasources.settings.export_policies.delete')}
                    onClick={() => onDelete(p)}
                    disabled={deleteMutation.isPending}
                  />
                </>
              ),
            },
          ]}
        />
      )}

      <ExportPolicyModal
        open={modalOpen}
        dsId={dsId}
        policy={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

function PolicyDetails({ policy }: { policy: ExportPolicy }) {
  const { t } = useTranslation();
  if (policy.mode === 'ROW_CAP' && policy.row_cap != null) {
    return (
      <span style={{ fontSize: 12 }}>
        {t('datasources.settings.export_policies.detail_row_cap', { count: policy.row_cap })}
      </span>
    );
  }
  if (policy.mode === 'DENY_CLASSIFIED') {
    if (policy.deny_classifications.length === 0) {
      return (
        <span style={{ fontSize: 12 }}>
          {t('datasources.settings.export_policies.detail_deny_any')}
        </span>
      );
    }
    return (
      <span style={{ fontSize: 12 }}>
        {policy.deny_classifications.map((c) => (
          <Tag key={c}>{dataClassificationLabel(t, c)}</Tag>
        ))}
      </span>
    );
  }
  return <span className="muted" style={{ fontSize: 12 }}>—</span>;
}

function AppliesToSummary({ policy }: { policy: ExportPolicy }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (policy.applies_to_roles.length > 0) {
    parts.push(
      t('datasources.settings.export_policies.applies_roles', {
        count: policy.applies_to_roles.length,
      }),
    );
  }
  if (policy.applies_to_group_ids.length > 0) {
    parts.push(
      t('datasources.settings.export_policies.applies_groups', {
        count: policy.applies_to_group_ids.length,
      }),
    );
  }
  if (policy.applies_to_user_ids.length > 0) {
    parts.push(
      t('datasources.settings.export_policies.applies_users', {
        count: policy.applies_to_user_ids.length,
      }),
    );
  }
  if (parts.length === 0) {
    return (
      <span className="muted" style={{ fontSize: 12 }}>
        {t('datasources.settings.export_policies.applies_everyone')}
      </span>
    );
  }
  return <span style={{ fontSize: 12 }}>{parts.join(' · ')}</span>;
}

interface ExportPolicyFormValues {
  mode: ExportPolicyMode;
  row_cap?: number | null;
  deny_classifications?: DataClassification[];
  applies_to_roles?: string[];
  applies_to_group_ids?: string[];
  applies_to_user_ids?: string[];
  enabled: boolean;
}

interface ExportPolicyModalProps {
  open: boolean;
  dsId: string;
  policy: ExportPolicy | null;
  onClose: () => void;
}

// A fixed, obviously-sample id so the watermark preview reads as an example, not a real query.
const PREVIEW_QUERY_ID = '00000000-0000-0000-0000-000000000000';
const PREVIEW_ROW_COUNT = 42;

function ExportPolicyModal({ open, dsId, policy, onClose }: ExportPolicyModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ExportPolicyFormValues>();
  const mode = Form.useWatch('mode', form);
  const rowCap = Form.useWatch('row_cap', form);
  const currentUser = useAuthStore((s) => s.user);

  const usersQuery = useQuery({
    queryKey: userKeys.list({ size: 100 }),
    queryFn: () => listUsers({ size: 100 }),
    enabled: open,
  });
  const groupsQuery = useQuery({
    queryKey: groupKeys.lists(),
    queryFn: () => listAllGroups(),
    enabled: open,
  });
  const rolesQuery = useQuery({
    queryKey: roleKeys.lists(),
    queryFn: listRoles,
    enabled: open,
  });
  const roleOptions = useMemo(
    () => roleSelectOptions(rolesQuery.data ?? [], t, 'name'),
    [rolesQuery.data, t],
  );
  const userOptions = useMemo(
    () =>
      (usersQuery.data?.content ?? [])
        .filter((u: User) => u.active)
        .map((u: User) => ({ value: u.id, label: userDisplay(u.display_name, u.email) })),
    [usersQuery.data],
  );
  const groupOptions = useMemo(
    () => (groupsQuery.data ?? []).map((g) => ({ value: g.id, label: g.name })),
    [groupsQuery.data],
  );

  useEffect(() => {
    if (!open) return;
    if (policy) {
      form.setFieldsValue({
        mode: policy.mode,
        row_cap: policy.row_cap ?? undefined,
        deny_classifications: policy.deny_classifications,
        applies_to_roles: policy.applies_to_roles,
        applies_to_group_ids: policy.applies_to_group_ids,
        applies_to_user_ids: policy.applies_to_user_ids,
        enabled: policy.enabled,
      });
    } else {
      form.resetFields();
    }
  }, [open, policy, form]);

  const saveMutation = useMutation({
    mutationFn: (input: CreateExportPolicyInput) =>
      policy
        ? updateExportPolicy(dsId, policy.id, input)
        : createExportPolicy(dsId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: exportPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.export_policies.save_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('datasources.settings.export_policies.save_error')),
      );
    },
  });

  const onFinish = (values: ExportPolicyFormValues) => {
    const input: CreateExportPolicyInput = {
      mode: values.mode,
      applies_to_roles: values.applies_to_roles ?? [],
      applies_to_group_ids: values.applies_to_group_ids ?? [],
      applies_to_user_ids: values.applies_to_user_ids ?? [],
      enabled: values.enabled,
    };
    // row_cap / deny_classifications are mode-scoped: the backend rejects them on other modes.
    if (values.mode === 'ROW_CAP' && values.row_cap != null) {
      input.row_cap = values.row_cap;
    }
    if (values.mode === 'DENY_CLASSIFIED') {
      input.deny_classifications = values.deny_classifications ?? [];
    }
    saveMutation.mutate(input);
  };

  const watermarked = mode === 'WATERMARK' || mode === 'ROW_CAP';
  const previewEmail = currentUser?.email ?? 'user@example.com';

  return (
    <Modal
      open={open}
      title={
        policy
          ? t('datasources.settings.export_policies.edit_title')
          : t('datasources.settings.export_policies.create_title')
      }
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={saveMutation.isPending}
      destroyOnHidden
      width={560}
    >
      <Form<ExportPolicyFormValues>
        form={form}
        layout="vertical"
        initialValues={{ mode: 'WATERMARK', enabled: true }}
        onFinish={onFinish}
      >
        <Form.Item
          name="mode"
          label={t('datasources.settings.export_policies.label_mode')}
          rules={[
            { required: true, message: t('datasources.settings.export_policies.mode_required') },
          ]}
        >
          <Select<ExportPolicyMode>
            options={enumOptions(EXPORT_POLICY_MODES, exportPolicyModeLabel, t)}
          />
        </Form.Item>

        {mode === 'ROW_CAP' && (
          <Form.Item
            name="row_cap"
            label={t('datasources.settings.export_policies.label_row_cap')}
            rules={[
              {
                required: true,
                message: t('datasources.settings.export_policies.row_cap_required'),
              },
              {
                type: 'number',
                min: 1,
                max: 1_000_000,
                message: t('datasources.settings.export_policies.row_cap_range'),
              },
            ]}
          >
            <InputNumber min={1} max={1_000_000} style={{ width: 160 }} />
          </Form.Item>
        )}

        {mode === 'DENY_CLASSIFIED' && (
          <Form.Item
            name="deny_classifications"
            label={t('datasources.settings.export_policies.label_deny_classifications')}
            extra={t('datasources.settings.export_policies.deny_classifications_hint')}
          >
            <Select<DataClassification[]>
              mode="multiple"
              allowClear
              options={enumOptions(DATA_CLASSIFICATIONS, dataClassificationLabel, t)}
            />
          </Form.Item>
        )}

        <Form.Item
          name="applies_to_roles"
          label={t('datasources.settings.export_policies.label_applies_roles')}
          extra={t('datasources.settings.export_policies.applies_hint')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            options={roleOptions}
            loading={rolesQuery.isLoading}
          />
        </Form.Item>

        <Form.Item
          name="applies_to_group_ids"
          label={t('datasources.settings.export_policies.label_applies_groups')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            optionFilterProp="label"
            options={groupOptions}
            loading={groupsQuery.isLoading}
          />
        </Form.Item>

        <Form.Item
          name="applies_to_user_ids"
          label={t('datasources.settings.export_policies.label_applies_users')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            optionFilterProp="label"
            options={userOptions}
            loading={usersQuery.isLoading}
          />
        </Form.Item>

        <Form.Item
          name="enabled"
          label={t('datasources.settings.export_policies.label_enabled')}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>

        {watermarked && (
          <div
            style={{
              padding: '10px 12px',
              background: 'var(--bg-sunken)',
              borderRadius: 6,
            }}
          >
            <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>
              {t('datasources.settings.export_policies.preview_label')}
            </div>
            <Typography.Text className="mono" style={{ fontSize: 12, display: 'block' }}>
              {watermarkHeader(previewEmail, new Date(), PREVIEW_QUERY_ID)}
            </Typography.Text>
            <Typography.Text className="mono" style={{ fontSize: 12, display: 'block' }}>
              {watermarkFooter(
                PREVIEW_ROW_COUNT,
                mode === 'ROW_CAP' && rowCap != null ? rowCap : null,
              )}
            </Typography.Text>
          </div>
        )}
      </Form>
    </Modal>
  );
}
