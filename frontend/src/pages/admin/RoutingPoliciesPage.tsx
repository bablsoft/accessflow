import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Skeleton,
  Switch,
  Table,
  TimePicker,
} from 'antd';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import {
  createRoutingPolicy,
  deleteRoutingPolicy,
  listRoutingPolicies,
  reorderRoutingPolicies,
  routingPolicyKeys,
  updateRoutingPolicy,
} from '@/api/routingPolicies';
import { listDatasources } from '@/api/datasources';
import { listAllGroups } from '@/api/groups';
import { listRoles, roleKeys } from '@/api/roles';
import { routingPolicyErrorMessage } from '@/utils/apiErrors';
import {
  COMPARISON_OPERATORS,
  CONDITION_OPERANDS,
  QUERY_TYPES,
  RISK_LEVELS,
  ROUTING_ACTIONS,
  WEEKDAYS,
  comparisonOperatorLabel,
  conditionOperandLabel,
  enumOptions,
  queryTypeLabel,
  riskLevelLabel,
  routingActionLabel,
  weekdayLabel,
} from '@/utils/enumLabels';
import { roleSelectOptions, type RoleOption } from '@/utils/roleOptions';
import { showApiError } from '@/utils/showApiError';
import {
  ROUTING_POLICY_DEFAULT_VALUES as DEFAULT_VALUES,
  actionRequiresApprovals,
  conditionSummary,
  conditionToForm,
  defaultRow,
  isCidr,
  minutesToTime,
  rowsToCondition,
  type RoutingConditionRow,
} from './routingPolicyForm';
import type {
  RoutingAction,
  RoutingConditionOperand,
  RoutingPolicy,
  RoutingPolicyWriteRequest,
} from '@/types/api';

type FormConditionRow = Omit<RoutingConditionRow, 'time_start_min' | 'time_end_min'> & {
  time_range?: [Dayjs, Dayjs] | null;
};

interface RoutingPolicyFormState {
  name: string;
  description?: string | null;
  datasource_id?: string | null;
  priority: number;
  enabled: boolean;
  action: RoutingAction;
  required_approvals?: number | null;
  reason?: string | null;
  match_type: 'ALL' | 'ANY';
  conditions: FormConditionRow[];
}

function minutesToDayjs(minutes: number | undefined): Dayjs {
  return dayjs(minutesToTime(minutes), 'HH:mm');
}

function dayjsToMinutes(value: Dayjs): number {
  return value.hour() * 60 + value.minute();
}

function toFormRow(row: RoutingConditionRow): FormConditionRow {
  const { time_start_min, time_end_min, ...rest } = row;
  if (row.operand === 'time_of_day') {
    return {
      ...rest,
      time_range: [minutesToDayjs(time_start_min), minutesToDayjs(time_end_min)],
    };
  }
  return { ...rest };
}

function fromFormRow(row: FormConditionRow): RoutingConditionRow {
  const { time_range, ...rest } = row;
  if (row.operand === 'time_of_day' && time_range && time_range[0] && time_range[1]) {
    return {
      ...rest,
      time_start_min: dayjsToMinutes(time_range[0]),
      time_end_min: dayjsToMinutes(time_range[1]),
    };
  }
  return { ...rest };
}

export function RoutingPoliciesPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<RoutingPolicy | null>(null);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<RoutingPolicyFormState>();

  const policiesQuery = useQuery({
    queryKey: routingPolicyKeys.lists(),
    queryFn: listRoutingPolicies,
  });

  const datasourcesQuery = useQuery({
    queryKey: ['datasources', 'list', { page: 0, size: 200 }],
    queryFn: () => listDatasources({ page: 0, size: 200 }),
    staleTime: 60_000,
  });

  const groupsQuery = useQuery({
    queryKey: ['groups', 'all'],
    queryFn: listAllGroups,
    staleTime: 60_000,
  });

  const rolesQuery = useQuery({
    queryKey: roleKeys.lists(),
    queryFn: listRoles,
    staleTime: 60_000,
  });
  const roleOptions = useMemo(
    () => roleSelectOptions(rolesQuery.data ?? [], t, 'name'),
    [rolesQuery.data, t],
  );

  const isOpen = creating || editing !== null;

  useEffect(() => {
    if (creating) {
      form.resetFields();
      form.setFieldsValue({
        ...DEFAULT_VALUES,
        conditions: DEFAULT_VALUES.conditions.map(toFormRow),
      });
    } else if (editing) {
      const parsed = conditionToForm(editing.condition);
      form.resetFields();
      form.setFieldsValue({
        name: editing.name,
        description: editing.description ?? '',
        datasource_id: editing.datasource_id,
        priority: editing.priority,
        enabled: editing.enabled,
        action: editing.action,
        required_approvals: editing.required_approvals ?? 2,
        reason: editing.reason ?? '',
        match_type: parsed.matchType,
        conditions: parsed.rows.map(toFormRow),
      });
      if (!parsed.supported) {
        message.warning(t('admin.routing_policies.condition_advanced_warning'));
      }
    }
  }, [creating, editing, form, message, t]);

  const closeModal = () => {
    setCreating(false);
    setEditing(null);
  };

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: routingPolicyKeys.all });

  const createMutation = useMutation({
    mutationFn: (payload: RoutingPolicyWriteRequest) => createRoutingPolicy(payload),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.routing_policies.create_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, routingPolicyErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: (vars: { id: string; payload: RoutingPolicyWriteRequest }) =>
      updateRoutingPolicy(vars.id, vars.payload),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.routing_policies.update_success'));
      closeModal();
    },
    onError: (err) => showApiError(message, err, routingPolicyErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteRoutingPolicy(id),
    onSuccess: () => {
      void invalidate();
      message.success(t('admin.routing_policies.delete_success'));
    },
    onError: (err) => showApiError(message, err, routingPolicyErrorMessage),
  });

  const reorderMutation = useMutation({
    mutationFn: (orderedIds: string[]) => reorderRoutingPolicies(orderedIds),
    onSuccess: () => void invalidate(),
    onError: (err) => showApiError(message, err, routingPolicyErrorMessage),
  });

  const onFinish = (values: RoutingPolicyFormState) => {
    const rows = (values.conditions ?? []).map(fromFormRow);
    const payload: RoutingPolicyWriteRequest = {
      name: values.name.trim(),
      description: values.description?.trim() || null,
      datasource_id: values.datasource_id ?? null,
      priority: values.priority,
      enabled: values.enabled,
      action: values.action,
      required_approvals: actionRequiresApprovals(values.action)
        ? values.required_approvals ?? null
        : null,
      reason: values.reason?.trim() || null,
      condition: rowsToCondition(values.match_type, rows),
    };
    if (creating) {
      createMutation.mutate(payload);
    } else if (editing) {
      updateMutation.mutate({ id: editing.id, payload });
    }
  };

  const onDelete = (policy: RoutingPolicy) =>
    modal.confirm({
      title: t('admin.routing_policies.delete_confirm_title'),
      content: t('admin.routing_policies.delete_confirm_body', { name: policy.name }),
      okType: 'danger',
      okText: t('common.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(policy.id),
    });

  const policies = useMemo(() => policiesQuery.data ?? [], [policiesQuery.data]);

  const toggleEnabled = (policy: RoutingPolicy, enabled: boolean) =>
    updateMutation.mutate({
      id: policy.id,
      payload: {
        name: policy.name,
        description: policy.description,
        datasource_id: policy.datasource_id,
        priority: policy.priority,
        enabled,
        action: policy.action,
        required_approvals: policy.required_approvals,
        reason: policy.reason,
        condition: policy.condition,
      },
    });

  const move = (index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= policies.length) return;
    const ordered = policies.map((p) => p.id);
    const moved = ordered[index];
    const other = ordered[target];
    if (moved === undefined || other === undefined) return;
    ordered[index] = other;
    ordered[target] = moved;
    reorderMutation.mutate(ordered);
  };

  const datasourceName = (id: string | null) =>
    id
      ? datasourcesQuery.data?.content.find((d) => d.id === id)?.name ?? id
      : t('admin.routing_policies.scope_org_wide');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-routing-policies"
        title={t('admin.routing_policies.title')}
        subtitle={t('admin.routing_policies.subtitle')}
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => policiesQuery.refetch()}>
              {t('common.refresh')}
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditing(null);
                setCreating(true);
              }}
            >
              {t('admin.routing_policies.add_button')}
            </Button>
          </>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {policiesQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} style={{ padding: 24 }} />
        ) : policiesQuery.isError ? (
          <EmptyState
            title={t('admin.routing_policies.load_error')}
            description={routingPolicyErrorMessage(policiesQuery.error)}
          />
        ) : policies.length === 0 ? (
          <EmptyState
            title={t('admin.routing_policies.title')}
            description={t('admin.routing_policies.empty')}
          />
        ) : (
          <Table<RoutingPolicy>
            rowKey="id"
            size="middle"
            dataSource={policies}
            scroll={{ x: 'max-content' }}
            pagination={false}
            columns={[
              {
                title: t('admin.routing_policies.col_order'),
                width: 90,
                render: (_v, _p, index) => (
                  <div style={{ display: 'flex', gap: 2 }}>
                    <Button
                      size="small"
                      type="text"
                      icon={<ArrowUpOutlined />}
                      aria-label={t('admin.routing_policies.move_up')}
                      disabled={index === 0 || reorderMutation.isPending}
                      onClick={() => move(index, -1)}
                    />
                    <Button
                      size="small"
                      type="text"
                      icon={<ArrowDownOutlined />}
                      aria-label={t('admin.routing_policies.move_down')}
                      disabled={index === policies.length - 1 || reorderMutation.isPending}
                      onClick={() => move(index, 1)}
                    />
                  </div>
                ),
              },
              {
                title: t('admin.routing_policies.col_priority'),
                dataIndex: 'priority',
                width: 90,
                render: (v) => <span className="mono">{v}</span>,
              },
              {
                title: t('admin.routing_policies.col_name'),
                dataIndex: 'name',
                render: (v) => <span style={{ fontWeight: 500 }}>{v}</span>,
              },
              {
                title: t('admin.routing_policies.col_action'),
                dataIndex: 'action',
                width: 150,
                render: (v: RoutingAction) => (
                  <Pill
                    fg="var(--fg-default)"
                    bg="var(--status-neutral-bg)"
                    border="var(--status-neutral-border)"
                    size="sm"
                  >
                    {routingActionLabel(t, v)}
                  </Pill>
                ),
              },
              {
                title: t('admin.routing_policies.col_scope'),
                dataIndex: 'datasource_id',
                width: 150,
                render: (v: string | null) => (
                  <span className="muted">{datasourceName(v)}</span>
                ),
              },
              {
                title: t('admin.routing_policies.col_condition'),
                dataIndex: 'condition',
                render: (_v, policy) => (
                  <span className="muted">{conditionSummary(t, policy.condition)}</span>
                ),
              },
              {
                title: t('admin.routing_policies.col_enabled'),
                dataIndex: 'enabled',
                width: 90,
                render: (v: boolean, policy) => (
                  <Switch
                    size="small"
                    checked={v}
                    aria-label={t('admin.routing_policies.col_enabled')}
                    onChange={(checked) => toggleEnabled(policy, checked)}
                  />
                ),
              },
              {
                title: t('admin.routing_policies.col_actions'),
                width: 110,
                render: (_v, policy) => (
                  <div style={{ display: 'flex', gap: 4 }}>
                    <Button
                      size="small"
                      type="text"
                      icon={<EditOutlined />}
                      aria-label={t('common.edit')}
                      onClick={() => {
                        setCreating(false);
                        setEditing(policy);
                      }}
                    />
                    <Button
                      size="small"
                      type="text"
                      icon={<DeleteOutlined />}
                      aria-label={t('common.delete')}
                      onClick={() => onDelete(policy)}
                    />
                  </div>
                ),
              },
            ]}
          />
        )}
      </div>

      <Modal
        open={isOpen}
        title={
          editing
            ? t('admin.routing_policies.edit_modal_title', { name: editing.name })
            : t('admin.routing_policies.create_modal_title')
        }
        onCancel={closeModal}
        onOk={() => form.submit()}
        okText={
          editing
            ? t('admin.routing_policies.save_update')
            : t('admin.routing_policies.save_create')
        }
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending || updateMutation.isPending}
        destroyOnHidden
        width={720}
      >
        <Form<RoutingPolicyFormState>
          form={form}
          layout="vertical"
          onFinish={onFinish}
        >
          <Form.Item
            name="name"
            label={t('admin.routing_policies.label_name')}
            rules={[{ required: true, max: 255, whitespace: true }]}
          >
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('admin.routing_policies.label_description')}
            rules={[{ max: 2000 }]}
          >
            <Input.TextArea rows={2} maxLength={2000} />
          </Form.Item>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
            <Form.Item
              name="datasource_id"
              label={t('admin.routing_policies.label_scope')}
            >
              <Select
                allowClear
                placeholder={t('admin.routing_policies.scope_org_wide')}
                options={(datasourcesQuery.data?.content ?? []).map((d) => ({
                  value: d.id,
                  label: d.name,
                }))}
              />
            </Form.Item>
            <Form.Item
              name="priority"
              label={t('admin.routing_policies.label_priority')}
              rules={[{ required: true, type: 'number', min: 0 }]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="enabled"
              label={t('admin.routing_policies.label_enabled')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item
              name="action"
              label={t('admin.routing_policies.label_action')}
              rules={[{ required: true }]}
            >
              <Select options={enumOptions(ROUTING_ACTIONS, routingActionLabel, t)} />
            </Form.Item>
            <Form.Item
              noStyle
              shouldUpdate={(prev, cur) => prev.action !== cur.action}
            >
              {() =>
                actionRequiresApprovals(form.getFieldValue('action')) ? (
                  <Form.Item
                    name="required_approvals"
                    label={t('admin.routing_policies.label_required_approvals')}
                    rules={[{ required: true, type: 'number', min: 1, max: 10 }]}
                  >
                    <InputNumber min={1} max={10} style={{ width: '100%' }} />
                  </Form.Item>
                ) : null
              }
            </Form.Item>
          </div>

          <Form.Item name="reason" label={t('admin.routing_policies.label_reason')} rules={[{ max: 500 }]}>
            <Input maxLength={500} />
          </Form.Item>

          <Form.Item name="match_type" label={t('admin.routing_policies.label_match_type')}>
            <Select
              options={[
                { value: 'ALL', label: t('admin.routing_policies.match_all') },
                { value: 'ANY', label: t('admin.routing_policies.match_any') },
              ]}
            />
          </Form.Item>

          <Form.Item label={t('admin.routing_policies.label_conditions')} required>
            <Form.List
              name="conditions"
              rules={[
                {
                  validator: async (_rule, items: FormConditionRow[]) => {
                    if (!items || items.length === 0) {
                      throw new Error(t('admin.routing_policies.validation_min_conditions'));
                    }
                  },
                },
              ]}
            >
              {(fields, { add, remove }, { errors }) => (
                <>
                  {fields.map(({ key, name }) => (
                    <div
                      key={key}
                      style={{
                        border: '1px solid var(--border-subtle)',
                        borderRadius: 6,
                        padding: 8,
                        marginBottom: 8,
                      }}
                    >
                      <div
                        style={{
                          display: 'grid',
                          gridTemplateColumns: '1fr 90px 32px',
                          gap: 8,
                          alignItems: 'center',
                        }}
                      >
                        <Form.Item
                          name={[name, 'operand']}
                          rules={[{ required: true }]}
                          style={{ marginBottom: 0 }}
                        >
                          <Select
                            options={enumOptions(CONDITION_OPERANDS, conditionOperandLabel, t)}
                            onChange={(operand: RoutingConditionOperand) => {
                              const rows = form.getFieldValue('conditions') as FormConditionRow[];
                              rows[name] = toFormRow(defaultRow(operand));
                              form.setFieldsValue({ conditions: rows });
                            }}
                          />
                        </Form.Item>
                        <Form.Item
                          name={[name, 'negate']}
                          valuePropName="checked"
                          tooltip={t('admin.routing_policies.negate_hint')}
                          style={{ marginBottom: 0 }}
                        >
                          <Switch
                            checkedChildren={t('admin.routing_policies.not_prefix')}
                            unCheckedChildren={t('admin.routing_policies.is_prefix')}
                          />
                        </Form.Item>
                        <Button
                          type="text"
                          icon={<DeleteOutlined />}
                          aria-label={t('admin.routing_policies.condition_remove')}
                          onClick={() => remove(name)}
                        />
                      </div>
                      <div style={{ marginTop: 8 }}>
                        <Form.Item
                          noStyle
                          shouldUpdate={(prev, cur) =>
                            prev.conditions?.[name]?.operand !== cur.conditions?.[name]?.operand
                          }
                        >
                          {() => (
                            <ConditionValueEditor
                              name={name}
                              operand={form.getFieldValue(['conditions', name, 'operand'])}
                              groups={groupsQuery.data ?? []}
                              roleOptions={roleOptions}
                            />
                          )}
                        </Form.Item>
                      </div>
                    </div>
                  ))}
                  <Button
                    type="dashed"
                    block
                    icon={<PlusOutlined />}
                    onClick={() => add(toFormRow(defaultRow('query_type')))}
                  >
                    {t('admin.routing_policies.condition_add')}
                  </Button>
                  <Form.ErrorList errors={errors} />
                </>
              )}
            </Form.List>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

interface ConditionValueEditorProps {
  name: number;
  operand: RoutingConditionOperand | undefined;
  groups: { id: string; name: string }[];
  roleOptions: RoleOption[];
}

function ConditionValueEditor({ name, operand, groups, roleOptions }: ConditionValueEditorProps) {
  const { t } = useTranslation();
  switch (operand) {
    case 'query_type':
      return (
        <Form.Item name={[name, 'query_types']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <Select mode="multiple" options={enumOptions(QUERY_TYPES, queryTypeLabel, t)} />
        </Form.Item>
      );
    case 'referenced_table':
      return (
        <Form.Item name={[name, 'table_globs']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <Select
            mode="tags"
            tokenSeparators={[',', ' ']}
            placeholder={t('admin.routing_policies.table_globs_placeholder')}
          />
        </Form.Item>
      );
    case 'risk_level':
      return (
        <Form.Item name={[name, 'risk_levels']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <Select mode="multiple" options={enumOptions(RISK_LEVELS, riskLevelLabel, t)} />
        </Form.Item>
      );
    case 'risk_score':
      return (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <Form.Item name={[name, 'score_operator']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
            <Select options={enumOptions(COMPARISON_OPERATORS, comparisonOperatorLabel, t)} />
          </Form.Item>
          <Form.Item
            name={[name, 'score_value']}
            rules={[{ required: true, type: 'number', min: 0, max: 100 }]}
            style={{ marginBottom: 0 }}
          >
            <InputNumber min={0} max={100} style={{ width: '100%' }} />
          </Form.Item>
        </div>
      );
    case 'requester_role':
      return (
        <Form.Item name={[name, 'roles']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <Select mode="multiple" options={roleOptions} />
        </Form.Item>
      );
    case 'requester_group':
      return (
        <Form.Item name={[name, 'group_ids']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <Select
            mode="multiple"
            placeholder={t('admin.routing_policies.groups_placeholder')}
            options={groups.map((g) => ({ value: g.id, label: g.name }))}
          />
        </Form.Item>
      );
    case 'time_of_day':
      return (
        <Form.Item name={[name, 'time_range']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <TimePicker.RangePicker format="HH:mm" minuteStep={15} style={{ width: '100%' }} />
        </Form.Item>
      );
    case 'day_of_week':
      return (
        <Form.Item name={[name, 'weekdays']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <Select mode="multiple" options={enumOptions(WEEKDAYS, weekdayLabel, t)} />
        </Form.Item>
      );
    case 'has_where':
    case 'has_limit':
    case 'transactional':
      return (
        <Form.Item
          name={[name, 'bool_value']}
          valuePropName="checked"
          label={t('admin.routing_policies.bool_present_label')}
          style={{ marginBottom: 0 }}
        >
          <Switch />
        </Form.Item>
      );
    case 'source_ip':
      return (
        <Form.Item
          name={[name, 'cidrs']}
          rules={[
            { required: true },
            {
              validator: (_rule, value: string[] | undefined) => {
                const bad = (value ?? []).filter((v) => !isCidr(v));
                return bad.length
                  ? Promise.reject(
                      new Error(t('admin.routing_policies.cidr_invalid', { value: bad.join(', ') })),
                    )
                  : Promise.resolve();
              },
            },
          ]}
          style={{ marginBottom: 0 }}
        >
          <Select
            mode="tags"
            tokenSeparators={[',', ' ']}
            placeholder={t('admin.routing_policies.cidrs_placeholder')}
          />
        </Form.Item>
      );
    case 'user_agent':
      return (
        <Form.Item name={[name, 'ua_patterns']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
          <Select
            mode="tags"
            tokenSeparators={[',']}
            placeholder={t('admin.routing_policies.user_agent_placeholder')}
          />
        </Form.Item>
      );
    case 'time_since_last_approval':
      return (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <Form.Item name={[name, 'tsla_operator']} rules={[{ required: true }]} style={{ marginBottom: 0 }}>
            <Select options={enumOptions(COMPARISON_OPERATORS, comparisonOperatorLabel, t)} />
          </Form.Item>
          <Form.Item
            name={[name, 'tsla_minutes']}
            rules={[{ required: true, type: 'number', min: 0 }]}
            style={{ marginBottom: 0 }}
          >
            <InputNumber
              min={0}
              style={{ width: '100%' }}
              addonAfter={t('admin.routing_policies.minutes_suffix')}
            />
          </Form.Item>
        </div>
      );
    case 'cicd_origin':
      return (
        <Form.Item
          name={[name, 'bool_value']}
          valuePropName="checked"
          label={t('admin.routing_policies.cicd_present_label')}
          style={{ marginBottom: 0 }}
        >
          <Switch />
        </Form.Item>
      );
    default:
      return null;
  }
}
