import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Skeleton,
  Space,
  Switch,
  Table,
} from 'antd';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PolicySimulationDrawer } from '@/components/policies/PolicySimulationDrawer';
import {
  draftFingerprint,
  isRoutingDraftHighImpact,
  type RoutingDraftPayload,
} from '@/components/policies/policyImpact';
import { simulateRoutingPolicy } from '@/api/policySimulation';
import { routingSimulationSummary } from './routingSimulationSummary';
import { ConditionTreeEditor } from '@/components/conditions/ConditionTreeEditor';
import {
  fromRoutingFormRow,
  routingOperandSpecs,
  toRoutingFormRow,
  type RoutingFormConditionRow,
} from '@/components/conditions/routingOperands';
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
  ROUTING_ACTIONS,
  enumOptions,
  routingActionLabel,
} from '@/utils/enumLabels';
import { roleSelectOptions } from '@/utils/roleOptions';
import { showApiError } from '@/utils/showApiError';
import {
  ROUTING_POLICY_DEFAULT_VALUES as DEFAULT_VALUES,
  actionRequiresApprovals,
  conditionSummary,
  conditionToForm,
  rowsToCondition,
} from './routingPolicyForm';
import type {
  RoutingAction,
  RoutingPolicy,
  RoutingPolicyWriteRequest,
} from '@/types/api';

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
  conditions: RoutingFormConditionRow[];
}

export function RoutingPoliciesPage() {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<RoutingPolicy | null>(null);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<RoutingPolicyFormState>();
  // AF-630: the draft last simulated, so the save nudge can tell whether what is about to be
  // saved is still what the admin looked at.
  const [simulatedKey, setSimulatedKey] = useState('');
  const [simulationOpen, setSimulationOpen] = useState(false);

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
  const operandSpecs = useMemo(
    () => routingOperandSpecs(t, groupsQuery.data ?? [], roleOptions),
    [t, groupsQuery.data, roleOptions],
  );

  const isOpen = creating || editing !== null;

  useEffect(() => {
    if (creating) {
      form.resetFields();
      form.setFieldsValue({
        ...DEFAULT_VALUES,
        conditions: DEFAULT_VALUES.conditions.map(toRoutingFormRow),
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
        conditions: parsed.rows.map(toRoutingFormRow),
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

  /** The draft the Simulate button replays, built from whatever is in the form right now. */
  const buildSimulationPayload = (): RoutingDraftPayload | null => {
    const values = form.getFieldsValue();
    if (!values.name || !values.action) {
      return null;
    }
    const rows = (values.conditions ?? []).map(fromRoutingFormRow);
    return {
      datasource_id: values.datasource_id ?? null,
      draft: {
        replaces_policy_id: editing?.id ?? null,
        name: values.name.trim(),
        datasource_id: values.datasource_id ?? null,
        priority: values.priority,
        enabled: values.enabled,
        condition: rowsToCondition(values.match_type, rows),
        action: values.action,
        required_approvals: actionRequiresApprovals(values.action)
          ? values.required_approvals ?? null
          : null,
        reason: values.reason?.trim() || null,
      },
    };
  };

  const openSimulation = () => {
    // Validate first so the draft we replay is one the API would actually accept. Field errors
    // render inline, so a rejection needs no further reporting.
    form.validateFields().then(
      () => setSimulationOpen(true),
      () => undefined,
    );
  };

  /**
   * Soft nudge, never a gate: a wide-blast-radius policy that has not been simulated in its
   * current shape gets one confirm step, with saving always available.
   */
  const confirmHighImpactSave = (submit: () => void) => {
    const payload = buildSimulationPayload();
    const values = form.getFieldsValue();
    const unsimulated = draftFingerprint(payload) !== simulatedKey;
    if (!unsimulated || !isRoutingDraftHighImpact(values)) {
      submit();
      return;
    }
    modal.confirm({
      title: t('policySimulation.nudge_title'),
      content: t('policySimulation.nudge_body'),
      okText: t('policySimulation.nudge_simulate'),
      cancelText: t('policySimulation.nudge_save'),
      // AntD calls onCancel for Esc as well as the Cancel button, and here onCancel is the write
      // path. Esc must not be able to commit a policy the admin has not looked at.
      keyboard: false,
      onOk: () => setSimulationOpen(true),
      onCancel: submit,
    });
  };

  const onFinish = (values: RoutingPolicyFormState) => {
    const rows = (values.conditions ?? []).map(fromRoutingFormRow);
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
        onOk={() => confirmHighImpactSave(() => form.submit())}
        okText={
          editing
            ? t('admin.routing_policies.save_update')
            : t('admin.routing_policies.save_create')
        }
        cancelText={t('common.cancel')}
        confirmLoading={createMutation.isPending || updateMutation.isPending}
        destroyOnHidden
        width={720}
        footer={(_, { OkBtn, CancelBtn }) => (
          <Flex justify="space-between" align="center">
            <Button onClick={openSimulation}>
              {t('policySimulation.run')}
            </Button>
            <Space>
              <CancelBtn />
              <OkBtn />
            </Space>
          </Flex>
        )}
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

          <ConditionTreeEditor operands={operandSpecs} defaultOperand="query_type" />
        </Form>
      </Modal>

      <PolicySimulationDrawer
        open={simulationOpen}
        onClose={() => setSimulationOpen(false)}
        title={t('policySimulation.title')}
        draftKey={draftFingerprint(buildSimulationPayload())}
        run={(window) => {
          const payload = buildSimulationPayload();
          if (!payload) {
            return Promise.reject(new Error(t('policySimulation.incomplete_draft')));
          }
          return simulateRoutingPolicy({ ...payload, ...window });
        }}
        summarize={(result) => routingSimulationSummary(result, t)}
        onSimulated={setSimulatedKey}
      />
    </div>
  );
}
