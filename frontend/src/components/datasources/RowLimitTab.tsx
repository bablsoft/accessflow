import { useEffect, useMemo, useState } from 'react';
import {
  App,
  AutoComplete,
  Button,
  Form,
  InputNumber,
  Modal,
  Select,
  Switch,
  Table,
  Tooltip,
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
  createRowLimitPolicy,
  deleteRowLimitPolicy,
  listRowLimitPolicies,
  rowLimitPolicyKeys,
  updateRowLimitPolicy,
} from '@/api/rowLimitPolicies';
import { datasourceKeys, getDatasourceSchema } from '@/api/datasources';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import { listRoles, roleKeys } from '@/api/roles';
import { roleSelectOptions } from '@/utils/roleOptions';
import { userDisplay } from '@/utils/userDisplay';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { CreateRowLimitPolicyInput, RowLimitPolicy, User } from '@/types/api';
import { renderUserOption } from '@/components/common/renderUserOption';

const MAX_ROWS_CEILING = 1_000_000;

export function RowLimitTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<RowLimitPolicy | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const policiesQuery = useQuery({
    queryKey: rowLimitPolicyKeys.list(dsId),
    queryFn: () => listRowLimitPolicies(dsId),
  });
  const policies = policiesQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (policyId: string) => deleteRowLimitPolicy(dsId, policyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: rowLimitPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.row_limits.delete_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('datasources.settings.row_limits.delete_error')),
      );
    },
  });

  const onAdd = () => {
    setEditing(null);
    setModalOpen(true);
  };

  const onEdit = (policy: RowLimitPolicy) => {
    setEditing(policy);
    setModalOpen(true);
  };

  const onDelete = (policy: RowLimitPolicy) => {
    modal.confirm({
      title: t('datasources.settings.row_limits.delete_confirm_title'),
      content: t('datasources.settings.row_limits.delete_confirm_body'),
      okType: 'danger',
      okText: t('datasources.settings.row_limits.delete'),
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
          <div style={{ fontWeight: 600 }}>{t('datasources.settings.row_limits.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('datasources.settings.row_limits.description')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>
          {t('datasources.settings.row_limits.add')}
        </Button>
      </div>

      {!policiesQuery.isLoading && policies.length === 0 ? (
        <EmptyState
          title={t('datasources.settings.row_limits.empty_title')}
          description={t('datasources.settings.row_limits.empty_description')}
        />
      ) : (
        <Table<RowLimitPolicy>
          rowKey="id"
          size="middle"
          loading={policiesQuery.isLoading}
          dataSource={policies}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('datasources.settings.row_limits.col_table'),
              render: (_v, p) => (
                <span className="mono" style={{ fontSize: 12 }}>
                  {p.schema_name ? `${p.schema_name}.${p.table_name}` : p.table_name}
                </span>
              ),
            },
            {
              title: t('datasources.settings.row_limits.col_max_rows'),
              dataIndex: 'max_rows',
              width: 140,
              render: (v: number) => v.toLocaleString(),
            },
            {
              title: t('datasources.settings.row_limits.col_applies_to'),
              render: (_v, p) => <AppliesToSummary policy={p} />,
            },
            {
              title: t('datasources.settings.row_limits.col_enabled'),
              width: 90,
              align: 'center',
              render: (_v, p) => {
                const label = p.enabled
                  ? t('datasources.settings.row_limits.label_enabled')
                  : t('datasources.settings.row_limits.state_disabled');
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
              title: t('datasources.settings.row_limits.col_actions'),
              width: 120,
              align: 'right',
              render: (_v, p) => (
                <>
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={t('datasources.settings.row_limits.edit')}
                    onClick={() => onEdit(p)}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('datasources.settings.row_limits.delete')}
                    onClick={() => onDelete(p)}
                    disabled={deleteMutation.isPending}
                  />
                </>
              ),
            },
          ]}
        />
      )}

      <RowLimitPolicyModal
        open={modalOpen}
        dsId={dsId}
        policy={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

function AppliesToSummary({ policy }: { policy: RowLimitPolicy }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (policy.applies_to_roles.length > 0) {
    parts.push(
      t('datasources.settings.row_limits.applies_roles', {
        count: policy.applies_to_roles.length,
      }),
    );
  }
  if (policy.applies_to_group_ids.length > 0) {
    parts.push(
      t('datasources.settings.row_limits.applies_groups', {
        count: policy.applies_to_group_ids.length,
      }),
    );
  }
  if (policy.applies_to_user_ids.length > 0) {
    parts.push(
      t('datasources.settings.row_limits.applies_users', {
        count: policy.applies_to_user_ids.length,
      }),
    );
  }
  if (parts.length === 0) {
    return (
      <span className="muted" style={{ fontSize: 12 }}>
        {t('datasources.settings.row_limits.applies_everyone')}
      </span>
    );
  }
  return <span style={{ fontSize: 12 }}>{parts.join(' · ')}</span>;
}

interface RowLimitFormValues {
  schema_name?: string;
  table_name: string;
  max_rows: number;
  applies_to_roles?: string[];
  applies_to_group_ids?: string[];
  applies_to_user_ids?: string[];
  enabled: boolean;
}

interface RowLimitPolicyModalProps {
  open: boolean;
  dsId: string;
  policy: RowLimitPolicy | null;
  onClose: () => void;
}

function RowLimitPolicyModal({ open, dsId, policy, onClose }: RowLimitPolicyModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<RowLimitFormValues>();
  const selectedSchema = Form.useWatch('schema_name', form);

  const schemaQuery = useQuery({
    queryKey: datasourceKeys.schema(dsId),
    queryFn: () => getDatasourceSchema(dsId),
    enabled: open,
    staleTime: 5 * 60_000,
    retry: false,
  });
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
  const schemaOptions = useMemo(
    () => (schemaQuery.data?.schemas ?? []).map((s) => ({ value: s.name })),
    [schemaQuery.data],
  );
  const tableOptions = useMemo(() => {
    const wanted = selectedSchema?.trim().toLowerCase();
    const names = new Set<string>();
    for (const s of schemaQuery.data?.schemas ?? []) {
      if (wanted && s.name.toLowerCase() !== wanted) continue;
      for (const tbl of s.tables) names.add(tbl.name);
    }
    return [...names].sort().map((name) => ({ value: name }));
  }, [schemaQuery.data, selectedSchema]);
  const userOptions = useMemo(
    () =>
      (usersQuery.data?.content ?? [])
        .filter((u: User) => u.active)
        .map((u: User) => ({
          value: u.id,
          label: userDisplay(u.display_name, u.email),
          principal_type: u.principal_type ?? 'HUMAN',
        })),
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
        schema_name: policy.schema_name ?? undefined,
        table_name: policy.table_name,
        max_rows: policy.max_rows,
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
    mutationFn: (input: CreateRowLimitPolicyInput) =>
      policy ? updateRowLimitPolicy(dsId, policy.id, input) : createRowLimitPolicy(dsId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: rowLimitPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.row_limits.save_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('datasources.settings.row_limits.save_error')),
      );
    },
  });

  const onFinish = (values: RowLimitFormValues) => {
    const schema = values.schema_name?.trim();
    saveMutation.mutate({
      schema_name: schema ? schema : null,
      table_name: values.table_name.trim(),
      max_rows: values.max_rows,
      applies_to_roles: values.applies_to_roles ?? [],
      applies_to_group_ids: values.applies_to_group_ids ?? [],
      applies_to_user_ids: values.applies_to_user_ids ?? [],
      enabled: values.enabled,
    });
  };

  return (
    <Modal
      open={open}
      title={
        policy
          ? t('datasources.settings.row_limits.edit_title')
          : t('datasources.settings.row_limits.create_title')
      }
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={saveMutation.isPending}
      destroyOnHidden
      width={560}
    >
      <Form<RowLimitFormValues>
        form={form}
        name="row-limit-policy"
        layout="vertical"
        initialValues={{ enabled: true }}
        onFinish={onFinish}
      >
        <Form.Item
          name="schema_name"
          label={t('datasources.settings.row_limits.label_schema')}
          extra={t('datasources.settings.row_limits.schema_hint')}
          rules={[{ max: 255, message: t('datasources.settings.row_limits.schema_max') }]}
        >
          <AutoComplete
            options={schemaOptions}
            allowClear
            showSearch={{
              filterOption: (input, option) =>
                (option?.value ?? '').toLowerCase().includes(input.toLowerCase()),
            }}
            placeholder={t('datasources.settings.row_limits.placeholder_schema')}
          />
        </Form.Item>

        <Form.Item
          name="table_name"
          label={t('datasources.settings.row_limits.label_table')}
          rules={[
            {
              required: true,
              whitespace: true,
              message: t('datasources.settings.row_limits.table_required'),
            },
            { max: 255, message: t('datasources.settings.row_limits.table_max') },
          ]}
        >
          <AutoComplete
            options={tableOptions}
            showSearch={{
              filterOption: (input, option) =>
                (option?.value ?? '').toLowerCase().includes(input.toLowerCase()),
            }}
            placeholder={t('datasources.settings.row_limits.placeholder_table')}
          />
        </Form.Item>

        <Form.Item
          name="max_rows"
          label={t('datasources.settings.row_limits.label_max_rows')}
          extra={t('datasources.settings.row_limits.max_rows_hint')}
          rules={[
            { required: true, message: t('datasources.settings.row_limits.max_rows_required') },
            {
              type: 'number',
              min: 1,
              max: MAX_ROWS_CEILING,
              message: t('datasources.settings.row_limits.max_rows_range'),
            },
          ]}
        >
          <InputNumber min={1} max={MAX_ROWS_CEILING} precision={0} style={{ width: 160 }} />
        </Form.Item>

        <Form.Item
          name="applies_to_roles"
          label={t('datasources.settings.row_limits.label_applies_roles')}
          extra={t('datasources.settings.row_limits.applies_hint')}
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
          label={t('datasources.settings.row_limits.label_applies_groups')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            options={groupOptions}
            loading={groupsQuery.isLoading}
          />
        </Form.Item>

        <Form.Item
          name="applies_to_user_ids"
          label={t('datasources.settings.row_limits.label_applies_users')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            options={userOptions}
            optionRender={renderUserOption}
            loading={usersQuery.isLoading}
          />
        </Form.Item>

        <Form.Item
          name="enabled"
          label={t('datasources.settings.row_limits.label_enabled')}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
