import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Switch,
  Table,
  Tag,
  TimePicker,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  createDeploymentRoutingPolicy,
  deleteDeploymentRoutingPolicy,
  deploymentRoutingPolicyKeys,
  listDeploymentRoutingPolicies,
  updateDeploymentRoutingPolicy,
} from '@/api/deploymentRoutingPolicies';
import { deploymentPipelineKeys, listDeploymentEnvironments } from '@/api/deploymentPipelines';
import {
  PIPELINE_PROVIDERS,
  enumOptions,
  isoWeekdayLabel,
  pipelineProviderLabel,
  riskLevelLabel,
  routingActionLabel,
} from '@/utils/enumLabels';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import {
  actionRequiresApprovals,
  deploymentConditionsSummary,
  toCreateInput,
  toUpdateInput,
  type DeploymentRoutingPolicyFormValues,
} from './deploymentRoutingPolicyForm';
import { timezoneOptions } from './freezeWindowForm';
import type {
  DeploymentRoutingAction,
  DeploymentRoutingPolicy,
  PipelineProvider,
  RiskLevel,
} from '@/types/api';

const ACTIONS: DeploymentRoutingAction[] = [
  'AUTO_APPROVE',
  'AUTO_REJECT',
  'REQUIRE_APPROVALS',
  'ESCALATE',
];
const RISKS: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const ISO_DAYS = [1, 2, 3, 4, 5, 6, 7];

interface PolicyFormValues {
  name: string;
  scoped: boolean;
  action: DeploymentRoutingAction;
  required_approvals?: number | null;
  priority: number;
  enabled: boolean;
  environments?: string[];
  providers?: PipelineProvider[];
  min_risk_level?: RiskLevel | null;
  version_globs?: string[];
  days_of_week?: number[];
  time_range?: [Dayjs, Dayjs] | null;
  timezone?: string | null;
}

function toFormModel(
  values: PolicyFormValues,
  pipelineId: string,
): DeploymentRoutingPolicyFormValues {
  const [start, end] = values.time_range ?? [null, null];
  return {
    name: values.name,
    pipeline_id: values.scoped ? pipelineId : null,
    action: values.action,
    required_approvals: values.required_approvals ?? null,
    priority: values.priority,
    enabled: values.enabled,
    environments: values.environments ?? [],
    providers: values.providers ?? [],
    min_risk_level: values.min_risk_level ?? null,
    version_globs: values.version_globs ?? [],
    days_of_week: values.days_of_week ?? [],
    start_time: start ? start.format('HH:mm') : null,
    end_time: end ? end.format('HH:mm') : null,
    timezone: values.timezone ?? null,
  };
}

export function PipelineRoutingPoliciesTab({ pipelineId }: { pipelineId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [modalFor, setModalFor] = useState<'create' | DeploymentRoutingPolicy | null>(null);
  const [form] = Form.useForm<PolicyFormValues>();
  const action: DeploymentRoutingAction = Form.useWatch('action', form) ?? 'REQUIRE_APPROVALS';

  const policiesQuery = useQuery({
    queryKey: deploymentRoutingPolicyKeys.list(),
    queryFn: () => listDeploymentRoutingPolicies(),
  });
  const environmentsQuery = useQuery({
    queryKey: deploymentPipelineKeys.environments(pipelineId),
    queryFn: () => listDeploymentEnvironments(pipelineId),
  });

  const visible = useMemo(
    () =>
      (policiesQuery.data ?? []).filter(
        (p) => p.pipeline_id === pipelineId || p.pipeline_id == null,
      ),
    [policiesQuery.data, pipelineId],
  );

  useEffect(() => {
    if (modalFor === null) return;
    if (modalFor === 'create') {
      form.setFieldsValue({
        name: '',
        scoped: true,
        action: 'REQUIRE_APPROVALS',
        required_approvals: 1,
        priority: 100,
        enabled: true,
        environments: [],
        providers: [],
        min_risk_level: null,
        version_globs: [],
        days_of_week: [],
        time_range: null,
        timezone: null,
      });
    } else {
      const c = modalFor.conditions;
      form.setFieldsValue({
        name: modalFor.name,
        scoped: modalFor.pipeline_id != null,
        action: modalFor.action,
        required_approvals: modalFor.required_approvals,
        priority: modalFor.priority,
        enabled: modalFor.enabled,
        environments: [...c.environments],
        providers: [...c.providers],
        min_risk_level: c.min_risk_level,
        version_globs: [...c.version_globs],
        days_of_week: [...c.days_of_week],
        time_range:
          c.start_time && c.end_time
            ? [dayjs(c.start_time, 'HH:mm:ss'), dayjs(c.end_time, 'HH:mm:ss')]
            : null,
        timezone: c.timezone,
      });
    }
  }, [modalFor, form]);

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: deploymentRoutingPolicyKeys.all });

  const saveMutation = useMutation({
    mutationFn: (values: PolicyFormValues) => {
      const model = toFormModel(values, pipelineId);
      return modalFor === 'create'
        ? createDeploymentRoutingPolicy(toCreateInput(model))
        : updateDeploymentRoutingPolicy(
            (modalFor as DeploymentRoutingPolicy).id,
            toUpdateInput(model),
          );
    },
    onSuccess: () => {
      message.success(
        modalFor === 'create'
          ? t('deploygov.routingPolicies.created')
          : t('deploygov.routingPolicies.updated'),
      );
      setModalFor(null);
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteDeploymentRoutingPolicy(id),
    onSuccess: () => {
      message.success(t('deploygov.routingPolicies.deleted'));
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const columns: TableColumnsType<DeploymentRoutingPolicy> = [
    { title: t('deploygov.routingPolicies.priority'), dataIndex: 'priority', width: 90 },
    {
      title: t('deploygov.routingPolicies.name'),
      dataIndex: 'name',
      width: 200,
      render: (name: string, p) => (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          {name}
          {p.pipeline_id == null && (
            <Tag color="blue">{t('deploygov.routingPolicies.scopeGlobal')}</Tag>
          )}
        </span>
      ),
    },
    {
      title: t('deploygov.routingPolicies.conditions'),
      key: 'conditions',
      render: (_v, p) => (
        <span style={{ fontSize: 12 }}>{deploymentConditionsSummary(t, p.conditions)}</span>
      ),
    },
    {
      title: t('deploygov.routingPolicies.action'),
      dataIndex: 'action',
      width: 180,
      render: (a: DeploymentRoutingAction, p) => (
        <span>
          {routingActionLabel(t, a)}
          {p.required_approvals != null && (
            <span className="mono muted" style={{ fontSize: 11, marginLeft: 6 }}>
              ×{p.required_approvals}
            </span>
          )}
        </span>
      ),
    },
    {
      title: t('deploygov.routingPolicies.enabled'),
      dataIndex: 'enabled',
      width: 100,
      align: 'center' as const,
      render: (v: boolean) =>
        v ? '✓' : <span className="muted">{t('deploygov.routingPolicies.disabled')}</span>,
    },
    {
      title: '',
      key: 'actions',
      width: 160,
      render: (_v, row) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button size="small" onClick={() => setModalFor(row)}>
            {t('common.edit')}
          </Button>
          <Popconfirm
            title={t('deploygov.routingPolicies.deleteConfirm')}
            okText={t('common.delete')}
            cancelText={t('common.cancel')}
            onConfirm={() => deleteMutation.mutate(row.id)}
          >
            <Button size="small" danger>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 960 }}>
      <div className="muted" style={{ fontSize: 12, lineHeight: 1.55 }}>
        {t('deploygov.routingPolicies.hint')}
      </div>
      <div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalFor('create')}>
          {t('deploygov.routingPolicies.add')}
        </Button>
      </div>
      <Table<DeploymentRoutingPolicy>
        rowKey="id"
        size="small"
        pagination={false}
        loading={policiesQuery.isLoading}
        dataSource={visible}
        locale={{ emptyText: t('deploygov.routingPolicies.empty') }}
        columns={columns}
      />
      <Modal
        open={modalFor !== null}
        title={
          modalFor === 'create'
            ? t('deploygov.routingPolicies.createTitle')
            : t('deploygov.routingPolicies.editTitle')
        }
        onCancel={() => setModalFor(null)}
        okText={
          modalFor === 'create'
            ? t('deploygov.routingPolicies.create')
            : t('deploygov.routingPolicies.save')
        }
        confirmLoading={saveMutation.isPending}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form<PolicyFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
          {/* Parity with Create/UpdateDeploymentRoutingPolicyRequest: name @NotBlank
              @Size(1-255), action @NotNull, required_approvals @Min(1). */}
          <Form.Item
            name="name"
            label={t('deploygov.routingPolicies.labelName')}
            rules={[{ required: true, whitespace: true, max: 255 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="scoped"
            label={t('deploygov.routingPolicies.labelScope')}
            valuePropName="checked"
          >
            <Switch
              size="small"
              checkedChildren={t('deploygov.freezeWindows.scopeThisPipeline')}
              unCheckedChildren={t('deploygov.freezeWindows.scopeGlobal')}
            />
          </Form.Item>
          <Form.Item
            name="action"
            label={t('deploygov.routingPolicies.labelAction')}
            rules={[{ required: true }]}
          >
            <Select options={enumOptions(ACTIONS, routingActionLabel, t)} />
          </Form.Item>
          {actionRequiresApprovals(action) && (
            <Form.Item
              name="required_approvals"
              label={t('deploygov.routingPolicies.labelRequiredApprovals')}
              rules={[{ required: true, type: 'number', min: 1 }]}
            >
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          )}
          <Form.Item name="priority" label={t('deploygov.routingPolicies.labelPriority')}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="environments" label={t('deploygov.routingPolicies.labelEnvironments')}>
            <Select
              mode="tags"
              allowClear
              tokenSeparators={[',']}
              placeholder={t('deploygov.routingPolicies.environmentsPlaceholder')}
              options={(environmentsQuery.data ?? []).map((e) => ({
                value: e.name,
                label: e.name,
              }))}
            />
          </Form.Item>
          <Form.Item name="providers" label={t('deploygov.routingPolicies.labelProviders')}>
            <Select
              mode="multiple"
              allowClear
              options={enumOptions(PIPELINE_PROVIDERS, pipelineProviderLabel, t)}
            />
          </Form.Item>
          <Form.Item name="min_risk_level" label={t('deploygov.routingPolicies.labelMinRisk')}>
            <Select
              allowClear
              placeholder={t('deploygov.routingPolicies.minRiskAny')}
              options={enumOptions(RISKS, riskLevelLabel, t)}
            />
          </Form.Item>
          <Form.Item name="version_globs" label={t('deploygov.routingPolicies.labelVersionGlobs')}>
            <Select
              mode="tags"
              allowClear
              tokenSeparators={[',']}
              placeholder={t('deploygov.routingPolicies.versionGlobsPlaceholder')}
            />
          </Form.Item>
          <Form.Item name="days_of_week" label={t('deploygov.routingPolicies.labelDays')}>
            <Select
              mode="multiple"
              allowClear
              options={ISO_DAYS.map((d) => ({ value: d, label: isoWeekdayLabel(t, d) }))}
            />
          </Form.Item>
          <Form.Item name="time_range" label={t('deploygov.routingPolicies.labelTimeRange')}>
            <TimePicker.RangePicker format="HH:mm" minuteStep={15} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="timezone" label={t('deploygov.routingPolicies.labelTimezone')}>
            <Select
              allowClear
              showSearch
              options={timezoneOptions().map((z) => ({ value: z, label: z }))}
            />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('deploygov.routingPolicies.labelEnabled')}
            valuePropName="checked"
          >
            <Switch size="small" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
