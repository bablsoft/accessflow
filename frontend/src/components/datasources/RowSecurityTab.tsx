import { useEffect, useMemo, useState } from 'react';
import {
  App,
  AutoComplete,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Switch,
  Table,
  Tag,
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
  createRowSecurityPolicy,
  deleteRowSecurityPolicy,
  listRowSecurityPolicies,
  rowSecurityPolicyKeys,
  updateRowSecurityPolicy,
} from '@/api/rowSecurityPolicies';
import { datasourceKeys, getDatasourceSchema } from '@/api/datasources';
import { listUsers, userKeys } from '@/api/admin';
import { groupKeys, listAllGroups } from '@/api/groups';
import {
  enumOptions,
  ROW_SECURITY_OPERATORS,
  ROW_SECURITY_VALUE_TYPES,
  roleLabel,
  rowSecurityOperatorLabel,
  rowSecurityValueTypeLabel,
} from '@/utils/enumLabels';
import { userDisplay } from '@/utils/userDisplay';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  CreateRowSecurityPolicyInput,
  Role,
  RowSecurityOperator,
  RowSecurityPolicy,
  RowSecurityValueType,
  User,
} from '@/types/api';

const APPLIES_ROLE_VALUES: Role[] = ['ADMIN', 'REVIEWER', 'ANALYST', 'READONLY'];
const BUILT_IN_VARIABLES = [':user.id', ':user.email', ':user.role', ':user.groups'];

export function RowSecurityTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<RowSecurityPolicy | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const policiesQuery = useQuery({
    queryKey: rowSecurityPolicyKeys.list(dsId),
    queryFn: () => listRowSecurityPolicies(dsId),
  });
  const policies = policiesQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (policyId: string) => deleteRowSecurityPolicy(dsId, policyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: rowSecurityPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.row_security.delete_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('datasources.settings.row_security.delete_error')));
    },
  });

  const onAdd = () => {
    setEditing(null);
    setModalOpen(true);
  };

  const onEdit = (policy: RowSecurityPolicy) => {
    setEditing(policy);
    setModalOpen(true);
  };

  const onDelete = (policy: RowSecurityPolicy) => {
    modal.confirm({
      title: t('datasources.settings.row_security.delete_confirm_title'),
      content: t('datasources.settings.row_security.delete_confirm_body'),
      okType: 'danger',
      okText: t('datasources.settings.row_security.delete'),
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
          <div style={{ fontWeight: 600 }}>{t('datasources.settings.row_security.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('datasources.settings.row_security.description')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>
          {t('datasources.settings.row_security.add')}
        </Button>
      </div>

      {!policiesQuery.isLoading && policies.length === 0 ? (
        <EmptyState
          title={t('datasources.settings.row_security.empty_title')}
          description={t('datasources.settings.row_security.empty_description')}
        />
      ) : (
        <Table<RowSecurityPolicy>
          rowKey="id"
          size="middle"
          loading={policiesQuery.isLoading}
          dataSource={policies}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('datasources.settings.row_security.col_table'),
              dataIndex: 'table_name',
              render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
            },
            {
              title: t('datasources.settings.row_security.col_predicate'),
              render: (_v, p) => (
                <span className="mono" style={{ fontSize: 12 }}>
                  {`${p.column_name} ${rowSecurityOperatorLabel(t, p.operator)} ${p.value_expression}`}
                </span>
              ),
            },
            {
              title: t('datasources.settings.row_security.col_applies'),
              render: (_v, p) => <AppliesSummary policy={p} />,
            },
            {
              title: t('datasources.settings.row_security.col_enabled'),
              width: 90,
              align: 'center',
              render: (_v, p) => {
                const label = p.enabled
                  ? t('datasources.settings.row_security.label_enabled')
                  : t('datasources.settings.row_security.state_disabled');
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
              title: t('datasources.settings.row_security.col_actions'),
              width: 120,
              align: 'right',
              render: (_v, p) => (
                <>
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={t('datasources.settings.row_security.edit')}
                    onClick={() => onEdit(p)}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('datasources.settings.row_security.delete')}
                    onClick={() => onDelete(p)}
                    disabled={deleteMutation.isPending}
                  />
                </>
              ),
            },
          ]}
        />
      )}

      <RowSecurityPolicyModal
        open={modalOpen}
        dsId={dsId}
        policy={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

function AppliesSummary({ policy }: { policy: RowSecurityPolicy }) {
  const { t } = useTranslation();
  const parts: string[] = [];
  if (policy.applies_to_roles.length > 0) {
    parts.push(t('datasources.settings.row_security.applies_roles', {
      count: policy.applies_to_roles.length,
    }));
  }
  if (policy.applies_to_group_ids.length > 0) {
    parts.push(t('datasources.settings.row_security.applies_groups', {
      count: policy.applies_to_group_ids.length,
    }));
  }
  if (policy.applies_to_user_ids.length > 0) {
    parts.push(t('datasources.settings.row_security.applies_users', {
      count: policy.applies_to_user_ids.length,
    }));
  }
  if (parts.length === 0) {
    return (
      <Tag color="orange">{t('datasources.settings.row_security.applies_everyone')}</Tag>
    );
  }
  return <span style={{ fontSize: 12 }}>{parts.join(' · ')}</span>;
}

interface RowSecurityFormValues {
  table_name: string;
  column_name: string;
  operator: RowSecurityOperator;
  value_type: RowSecurityValueType;
  value_expression: string;
  applies_to_roles?: string[];
  applies_to_group_ids?: string[];
  applies_to_user_ids?: string[];
  enabled: boolean;
}

interface RowSecurityPolicyModalProps {
  open: boolean;
  dsId: string;
  policy: RowSecurityPolicy | null;
  onClose: () => void;
}

function RowSecurityPolicyModal({ open, dsId, policy, onClose }: RowSecurityPolicyModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<RowSecurityFormValues>();
  const valueType = Form.useWatch('value_type', form);

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

  const tableOptions = useMemo(
    () =>
      (schemaQuery.data?.schemas ?? []).flatMap((s) =>
        s.tables.map((tbl) => ({ value: `${s.name}.${tbl.name}` })),
      ),
    [schemaQuery.data],
  );
  const columnOptions = useMemo(() => {
    const names = new Set<string>();
    for (const s of schemaQuery.data?.schemas ?? []) {
      for (const tbl of s.tables) {
        for (const col of tbl.columns) names.add(col.name);
      }
    }
    return [...names].sort().map((name) => ({ value: name }));
  }, [schemaQuery.data]);
  const variableOptions = useMemo(() => BUILT_IN_VARIABLES.map((v) => ({ value: v })), []);
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
        table_name: policy.table_name,
        column_name: policy.column_name,
        operator: policy.operator,
        value_type: policy.value_type,
        value_expression:
          policy.value_type === 'VARIABLE' ? `:${policy.value_expression}` : policy.value_expression,
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
    mutationFn: (input: CreateRowSecurityPolicyInput) =>
      policy
        ? updateRowSecurityPolicy(dsId, policy.id, input)
        : createRowSecurityPolicy(dsId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: rowSecurityPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.row_security.save_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('datasources.settings.row_security.save_error')));
    },
  });

  const onFinish = (values: RowSecurityFormValues) => {
    saveMutation.mutate({
      table_name: values.table_name.trim(),
      column_name: values.column_name.trim(),
      operator: values.operator,
      value_type: values.value_type,
      value_expression: values.value_expression.trim(),
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
          ? t('datasources.settings.row_security.edit_title')
          : t('datasources.settings.row_security.create_title')
      }
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={saveMutation.isPending}
      destroyOnHidden
      width={560}
    >
      <Form<RowSecurityFormValues>
        form={form}
        layout="vertical"
        initialValues={{ operator: 'EQUALS', value_type: 'VARIABLE', enabled: true }}
        onFinish={onFinish}
      >
        <Form.Item
          name="table_name"
          label={t('datasources.settings.row_security.label_table')}
          rules={[
            { required: true, message: t('datasources.settings.row_security.table_required') },
            { max: 512, message: t('datasources.settings.row_security.table_max') },
          ]}
        >
          <AutoComplete
            options={tableOptions}
            filterOption={(input, option) =>
              (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
            }
            placeholder={t('datasources.settings.row_security.placeholder_table')}
          />
        </Form.Item>

        <Form.Item
          name="column_name"
          label={t('datasources.settings.row_security.label_column')}
          rules={[
            { required: true, message: t('datasources.settings.row_security.column_required') },
            { max: 512, message: t('datasources.settings.row_security.column_max') },
          ]}
        >
          <AutoComplete
            options={columnOptions}
            filterOption={(input, option) =>
              (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
            }
            placeholder={t('datasources.settings.row_security.placeholder_column')}
          />
        </Form.Item>

        <Form.Item
          name="operator"
          label={t('datasources.settings.row_security.label_operator')}
          rules={[
            { required: true, message: t('datasources.settings.row_security.operator_required') },
          ]}
        >
          <Select<RowSecurityOperator>
            options={enumOptions(ROW_SECURITY_OPERATORS, rowSecurityOperatorLabel, t)}
          />
        </Form.Item>

        <Form.Item
          name="value_type"
          label={t('datasources.settings.row_security.label_value_type')}
          rules={[
            {
              required: true,
              message: t('datasources.settings.row_security.value_type_required'),
            },
          ]}
        >
          <Select<RowSecurityValueType>
            options={enumOptions(ROW_SECURITY_VALUE_TYPES, rowSecurityValueTypeLabel, t)}
          />
        </Form.Item>

        <Form.Item
          name="value_expression"
          label={t('datasources.settings.row_security.label_value')}
          extra={
            valueType === 'VARIABLE'
              ? t('datasources.settings.row_security.value_variable_hint')
              : t('datasources.settings.row_security.value_literal_hint')
          }
          rules={[
            { required: true, message: t('datasources.settings.row_security.value_required') },
            { max: 512, message: t('datasources.settings.row_security.value_max') },
          ]}
        >
          {valueType === 'VARIABLE' ? (
            <AutoComplete
              options={variableOptions}
              placeholder=":user.region"
            />
          ) : (
            <Input placeholder="EU" />
          )}
        </Form.Item>

        <Form.Item
          name="applies_to_roles"
          label={t('datasources.settings.row_security.label_applies_roles')}
        >
          <Select<string[]>
            mode="multiple"
            allowClear
            options={enumOptions(APPLIES_ROLE_VALUES, roleLabel, t)}
            placeholder={t('datasources.settings.row_security.applies_everyone')}
          />
        </Form.Item>

        <Form.Item
          name="applies_to_group_ids"
          label={t('datasources.settings.row_security.label_applies_groups')}
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
          label={t('datasources.settings.row_security.label_applies_users')}
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
          label={t('datasources.settings.row_security.label_enabled')}
          valuePropName="checked"
        >
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
