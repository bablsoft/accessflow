import { useEffect, useState } from 'react';
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
} from 'antd';
import type { FormInstance, TableColumnsType } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  createDeploymentEnvironment,
  deleteDeploymentEnvironment,
  deploymentPipelineKeys,
  listDeploymentEnvironments,
  updateDeploymentEnvironment,
} from '@/api/deploymentPipelines';
import { deploymentVersionKeys } from '@/api/deploymentVersions';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { nextSortOrder } from './environmentLadder';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import {
  VERIFICATION_CHECK_KINDS,
  VERIFICATION_COMPARATORS,
  verificationCheckKindLabel,
  verificationComparatorLabel,
} from '@/utils/enumLabels';
import type { DeploymentEnvironment } from '@/types/api';
import {
  VERIFICATION_LIMITS as LIMITS,
  defaultCheckValues,
  defaultVerificationValues,
  isDuplicateCheckName,
  isHttpUrl,
  maxSamplesInWindow,
  toVerificationFormValues,
  toVerificationWire,
  verificationCheckCount,
  type VerificationFormValues,
} from './verificationForm';

interface EnvironmentFormValues {
  name: string;
  tags: string[];
  /** A cleared InputNumber yields null; the server then appends to the ladder (#877). */
  sort_order: number | null;
  require_review: boolean;
  required_approvals?: number | null;
  review_plan_id?: string | null;
  allow_break_glass: boolean;
  change_ticket_required: boolean;
  datasource_id?: string | null;
  verification: VerificationFormValues;
}

const DATASOURCE_PAGE = { page: 0, size: 200 };

export function PipelineEnvironmentsTab({ pipelineId }: { pipelineId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [modalFor, setModalFor] = useState<'create' | DeploymentEnvironment | null>(null);
  const [form] = Form.useForm<EnvironmentFormValues>();

  const envsQuery = useQuery({
    queryKey: deploymentPipelineKeys.environments(pipelineId),
    queryFn: () => listDeploymentEnvironments(pipelineId),
  });
  // Always loaded: the table's review-plan column resolves names from it too.
  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: () => listReviewPlans(),
  });
  // Likewise for the table's Database column (#877).
  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list(DATASOURCE_PAGE),
    queryFn: () => listDatasources(DATASOURCE_PAGE),
  });

  useEffect(() => {
    if (modalFor === null) return;
    if (modalFor === 'create') {
      form.setFieldsValue({
        name: '',
        tags: [],
        sort_order: nextSortOrder(envsQuery.data ?? []),
        require_review: true,
        required_approvals: null,
        review_plan_id: null,
        allow_break_glass: false,
        change_ticket_required: false,
        datasource_id: null,
        verification: defaultVerificationValues(),
      });
    } else {
      form.setFieldsValue({
        name: modalFor.name,
        tags: modalFor.tags,
        sort_order: modalFor.sort_order,
        require_review: modalFor.require_review,
        required_approvals: modalFor.required_approvals,
        review_plan_id: modalFor.review_plan_id,
        allow_break_glass: modalFor.allow_break_glass,
        change_ticket_required: modalFor.change_ticket_required,
        datasource_id: modalFor.datasource_id,
        verification: toVerificationFormValues(modalFor.verification),
      });
    }
  }, [modalFor, form, envsQuery.data]);

  const invalidate = () => {
    void queryClient.invalidateQueries({
      queryKey: deploymentPipelineKeys.environments(pipelineId),
    });
    // Matrix rows embed the environment's name, tags and sort order, so an edit here goes stale
    // on the Versions tab one tab over unless both version caches are dropped too.
    void queryClient.invalidateQueries({ queryKey: deploymentVersionKeys.matrix(pipelineId) });
    return queryClient.invalidateQueries({ queryKey: deploymentVersionKeys.lists() });
  };

  const saveMutation = useMutation({
    mutationFn: (values: EnvironmentFormValues) => {
      // Read from the store, not `values`: fields under a switched-off section are unmounted.
      const verification = toVerificationWire(
        form.getFieldValue('verification') as VerificationFormValues | undefined,
      );
      if (modalFor === 'create') {
        return createDeploymentEnvironment(pipelineId, {
          name: values.name,
          tags: values.tags ?? [],
          sort_order: values.sort_order ?? null,
          require_review: values.require_review,
          required_approvals: values.required_approvals ?? null,
          review_plan_id: values.review_plan_id ?? null,
          allow_break_glass: values.allow_break_glass,
          change_ticket_required: values.change_ticket_required,
          datasource_id: values.datasource_id ?? null,
          verification,
        });
      }
      return updateDeploymentEnvironment(pipelineId, (modalFor as DeploymentEnvironment).id, {
        name: values.name,
        // Always an array, never null/omitted: the server reads null as "leave unchanged", so
        // clearing every tag in the UI would otherwise silently no-op.
        tags: values.tags ?? [],
        sort_order: values.sort_order ?? null,
        require_review: values.require_review,
        required_approvals: values.required_approvals ?? null,
        clear_required_approvals: values.required_approvals == null,
        review_plan_id: values.review_plan_id ?? null,
        clear_review_plan: values.review_plan_id == null,
        allow_break_glass: values.allow_break_glass,
        change_ticket_required: values.change_ticket_required,
        datasource_id: values.datasource_id ?? null,
        clear_datasource: values.datasource_id == null,
        // Off ⇒ remove: the server reads a null config as "leave unchanged".
        verification,
        clear_verification: verification == null,
      });
    },
    onSuccess: () => {
      message.success(
        modalFor === 'create'
          ? t('deploygov.settings.envCreated')
          : t('deploygov.settings.envUpdated'),
      );
      setModalFor(null);
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const deleteMutation = useMutation({
    mutationFn: (environmentId: string) =>
      deleteDeploymentEnvironment(pipelineId, environmentId),
    onSuccess: () => {
      message.success(t('deploygov.settings.envDeleted'));
      void invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const planName = (id: string | null) =>
    id == null
      ? t('deploygov.settings.envInherited')
      : reviewPlansQuery.data?.find((p) => p.id === id)?.name ?? id;
  const datasourceOptions = (datasourcesQuery.data?.content ?? []).map((d) => ({
    value: d.id,
    label: d.name,
  }));
  // A bare UUID with no FK: a datasource deleted since binding still shows as its raw id.
  const datasourceName = (id: string | null) =>
    id == null ? null : datasourceOptions.find((o) => o.value === id)?.label ?? id;

  const columns: TableColumnsType<DeploymentEnvironment> = [
    { title: t('deploygov.settings.envSortOrder'), dataIndex: 'sort_order', width: 80 },
    { title: t('deploygov.settings.envName'), dataIndex: 'name', width: 180 },
    {
      title: t('deploygov.settings.envTags'),
      dataIndex: 'tags',
      width: 200,
      render: (tags: string[]) =>
        tags.length > 0 ? (
          tags.map((tag) => <Tag key={tag}>{tag}</Tag>)
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      title: t('deploygov.settings.envDatasource'),
      dataIndex: 'datasource_id',
      width: 160,
      render: (v: string | null) =>
        datasourceName(v) ?? <span className="muted">{t('deploygov.settings.envDatasourceNone')}</span>,
    },
    {
      title: t('deploygov.settings.envRequireReview'),
      dataIndex: 'require_review',
      width: 130,
      align: 'center' as const,
      render: (v: boolean) => (v ? '✓' : '—'),
    },
    {
      title: t('deploygov.settings.envRequiredApprovals'),
      dataIndex: 'required_approvals',
      width: 150,
      align: 'center' as const,
      render: (v: number | null) => v ?? <span className="muted">{t('deploygov.settings.envInherited')}</span>,
    },
    {
      title: t('deploygov.settings.envReviewPlan'),
      dataIndex: 'review_plan_id',
      width: 160,
      render: (v: string | null) => planName(v),
    },
    {
      title: t('deploygov.settings.envAllowBreakGlass'),
      dataIndex: 'allow_break_glass',
      width: 120,
      align: 'center' as const,
      render: (v: boolean) => (v ? '✓' : '—'),
    },
    {
      title: t('deploygov.settings.envChangeTicketRequired'),
      dataIndex: 'change_ticket_required',
      width: 120,
      align: 'center' as const,
      render: (v: boolean) => (v ? '✓' : '—'),
    },
    {
      title: t('deploygov.settings.verificationColumn'),
      key: 'verification',
      width: 120,
      align: 'center' as const,
      render: (_v, row) => {
        const count = verificationCheckCount(row.verification);
        return count == null ? (
          <span className="muted">—</span>
        ) : (
          t('deploygov.settings.verificationChecksCount', { n: count })
        );
      },
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
            title={t('deploygov.settings.envDeleteConfirm')}
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
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 860 }}>
      <div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalFor('create')}>
          {t('deploygov.settings.envAdd')}
        </Button>
      </div>
      <Table<DeploymentEnvironment>
        rowKey="id"
        size="small"
        pagination={false}
        loading={envsQuery.isLoading}
        dataSource={envsQuery.data ?? []}
        locale={{ emptyText: t('deploygov.settings.envEmpty') }}
        columns={columns}
      />
      <Modal
        open={modalFor !== null}
        title={
          modalFor === 'create'
            ? t('deploygov.settings.envCreateTitle')
            : t('deploygov.settings.envEditTitle')
        }
        onCancel={() => setModalFor(null)}
        okText={t('common.save')}
        confirmLoading={saveMutation.isPending}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        {/* Named so its control ids are namespaced: the settings page keeps the General tab
            mounted, and it declares `name` / `review_plan_id` too — bare ids would collide and
            mis-associate the labels. */}
        <Form<EnvironmentFormValues>
          form={form}
          name="deployment_environment"
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
          {/* Parity with Create/UpdateDeploymentEnvironmentRequest:
              name @NotBlank @Size(max 255), tags @Size(max 10) with each element @Size(max 32),
              sort_order @Min(0), required_approvals @Min(1). */}
          <Form.Item
            name="name"
            label={t('deploygov.settings.envName')}
            rules={[{ required: true, whitespace: true, max: 255 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="tags"
            label={t('deploygov.settings.envTags')}
            extra={t('deploygov.settings.envTagsHelp')}
            rules={[
              {
                validator: async (_, value: string[] | undefined) => {
                  if (!value) return;
                  if (value.length > 10) {
                    throw new Error(t('deploygov.settings.envTagsMax'));
                  }
                  if (value.some((tag) => (tag ?? '').length > 32)) {
                    throw new Error(t('deploygov.settings.envTagSize'));
                  }
                },
              },
            ]}
          >
            <Select
              mode="tags"
              tokenSeparators={[',']}
              placeholder={t('deploygov.settings.envTagsPlaceholder')}
            />
          </Form.Item>
          {/* Blank means "append" only on create — the update path reads null as "leave unchanged",
              so in edit mode the position is required rather than silently kept. */}
          <Form.Item
            name="sort_order"
            label={t('deploygov.settings.envSortOrder')}
            extra={
              modalFor === 'create'
                ? `${t('deploygov.settings.envSortOrderHelp')} ${t('deploygov.settings.envSortOrderAppendHint')}`
                : t('deploygov.settings.envSortOrderHelp')
            }
            rules={[{ type: 'number', min: 0 }, { required: modalFor !== 'create' }]}
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="datasource_id"
            label={t('deploygov.settings.envDatasource')}
            extra={t('deploygov.settings.envDatasourceHelp')}
          >
            <Select
              allowClear
              showSearch={{ optionFilterProp: 'label' }}
              placeholder={t('deploygov.settings.envDatasourceNone')}
              loading={datasourcesQuery.isLoading}
              options={datasourceOptions}
            />
          </Form.Item>
          <Form.Item
            name="require_review"
            label={t('deploygov.settings.envRequireReview')}
            valuePropName="checked"
          >
            <Switch size="small" />
          </Form.Item>
          <Form.Item
            name="required_approvals"
            label={t('deploygov.settings.envRequiredApprovals')}
            rules={[{ type: 'number', min: 1 }]}
          >
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              placeholder={t('deploygov.settings.envInherited')}
            />
          </Form.Item>
          <Form.Item name="review_plan_id" label={t('deploygov.settings.envReviewPlan')}>
            <Select
              allowClear
              placeholder={t('deploygov.settings.envInherited')}
              loading={reviewPlansQuery.isLoading}
              options={(reviewPlansQuery.data ?? []).map((p) => ({ value: p.id, label: p.name }))}
            />
          </Form.Item>
          <Form.Item
            name="allow_break_glass"
            label={t('deploygov.settings.envAllowBreakGlass')}
            valuePropName="checked"
          >
            <Switch size="small" />
          </Form.Item>
          <Form.Item
            name="change_ticket_required"
            label={t('deploygov.settings.envChangeTicketRequired')}
            valuePropName="checked"
            extra={t('deploygov.settings.envChangeTicketRequiredHelp')}
          >
            <Switch size="small" />
          </Form.Item>
          <VerificationSection form={form} />
        </Form>
      </Modal>
    </div>
  );
}

const VERIFICATION = 'verification';
const CHECKS = [VERIFICATION, 'checks'] as const;

/**
 * Parity with DeploymentVerificationConfigRequest / DeploymentVerificationCheckRequest (#1023)
 * and the network-free #1024 rules — see verificationForm.ts. Window 1..1440 min, interval
 * 0.5 min..window, threshold 1..20 and ≤ samples in the window, 1..10 checks, name required ≤ 64
 * and unique, URL required ≤ 2048 http(s), expected status 100..599, body ≤ 1024, query ≤ 4096,
 * comparator + threshold required on PromQL checks.
 */
function VerificationSection({ form }: { form: FormInstance<EnvironmentFormValues> }) {
  const { t } = useTranslation();
  return (
    <>
      <div style={{ fontWeight: 600, margin: '16px 0 8px' }}>
        {t('deploygov.settings.verificationSection')}
      </div>
      <Form.Item
        name={[VERIFICATION, 'enabled']}
        label={t('deploygov.settings.verificationEnabled')}
        valuePropName="checked"
        extra={t('deploygov.settings.verificationEnabledHelp')}
      >
        <Switch size="small" />
      </Form.Item>
      <Form.Item
        noStyle
        shouldUpdate={(prev: EnvironmentFormValues, cur: EnvironmentFormValues) =>
          prev.verification?.enabled !== cur.verification?.enabled
        }
      >
        {({ getFieldValue }) =>
          getFieldValue([VERIFICATION, 'enabled']) ? <VerificationFields form={form} /> : null
        }
      </Form.Item>
    </>
  );
}

function VerificationFields({ form }: { form: FormInstance<EnvironmentFormValues> }) {
  const { t } = useTranslation();
  const numberValue = (field: 'window_minutes' | 'interval_minutes') =>
    form.getFieldValue([VERIFICATION, field]) as number | null | undefined;
  const kindOptions = VERIFICATION_CHECK_KINDS.map((k) => ({
    value: k,
    label: verificationCheckKindLabel(t, k),
  }));
  const comparatorOptions = VERIFICATION_COMPARATORS.map((c) => ({
    value: c,
    label: verificationComparatorLabel(t, c),
  }));
  const httpUrlRule = {
    validator: async (_: unknown, value: string | null | undefined) => {
      if (value && value.trim() !== '' && !isHttpUrl(value)) {
        throw new Error(t('deploygov.settings.verificationUrlScheme'));
      }
    },
  };

  return (
    <>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <Form.Item
          name={[VERIFICATION, 'window_minutes']}
          label={t('deploygov.settings.verificationWindow')}
          style={{ flex: 1, minWidth: 140 }}
          rules={[
            { required: true, message: t('deploygov.settings.verificationWindowRange') },
            {
              type: 'number',
              min: LIMITS.windowMinMinutes,
              max: LIMITS.windowMaxMinutes,
              message: t('deploygov.settings.verificationWindowRange'),
            },
          ]}
        >
          <InputNumber
            min={LIMITS.windowMinMinutes}
            max={LIMITS.windowMaxMinutes}
            style={{ width: '100%' }}
            suffix={t('deploygov.settings.verificationMinutes')}
          />
        </Form.Item>
        <Form.Item
          name={[VERIFICATION, 'interval_minutes']}
          label={t('deploygov.settings.verificationInterval')}
          style={{ flex: 1, minWidth: 140 }}
          dependencies={[[VERIFICATION, 'window_minutes']]}
          rules={[
            { required: true, message: t('deploygov.settings.verificationIntervalRange') },
            {
              validator: async (_, value: number | null | undefined) => {
                if (value == null) return;
                const windowMinutes = numberValue('window_minutes');
                if (
                  value < LIMITS.intervalMinMinutes ||
                  (windowMinutes != null && value > windowMinutes)
                ) {
                  throw new Error(t('deploygov.settings.verificationIntervalRange'));
                }
              },
            },
          ]}
        >
          <InputNumber
            min={LIMITS.intervalMinMinutes}
            step={0.5}
            style={{ width: '100%' }}
            suffix={t('deploygov.settings.verificationMinutes')}
          />
        </Form.Item>
        <Form.Item
          name={[VERIFICATION, 'failure_threshold']}
          label={t('deploygov.settings.verificationThreshold')}
          style={{ flex: 1, minWidth: 140 }}
          dependencies={[
            [VERIFICATION, 'window_minutes'],
            [VERIFICATION, 'interval_minutes'],
          ]}
          rules={[
            { required: true, message: t('deploygov.settings.verificationThresholdRange') },
            {
              type: 'number',
              min: LIMITS.thresholdMin,
              max: LIMITS.thresholdMax,
              message: t('deploygov.settings.verificationThresholdRange'),
            },
            {
              validator: async (_, value: number | null | undefined) => {
                const samples = maxSamplesInWindow(
                  numberValue('window_minutes'),
                  numberValue('interval_minutes'),
                );
                if (value != null && samples != null && value > samples) {
                  throw new Error(
                    t('deploygov.settings.verificationThresholdExceedsSamples', { samples }),
                  );
                }
              },
            },
          ]}
        >
          <InputNumber
            min={LIMITS.thresholdMin}
            max={LIMITS.thresholdMax}
            precision={0}
            style={{ width: '100%' }}
          />
        </Form.Item>
      </div>
      <Form.List
        name={[...CHECKS]}
        rules={[
          {
            validator: async (_, checks: unknown[] | undefined) => {
              const count = checks?.length ?? 0;
              if (count < 1 || count > LIMITS.maxChecks) {
                throw new Error(t('deploygov.settings.verificationChecksRange'));
              }
            },
          },
        ]}
      >
        {(fields, { add, remove }, { errors }) => (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {fields.map((field, index) => (
              <div
                key={field.key}
                data-testid={`verification-check-${index}`}
                style={{
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius-md)',
                  padding: 12,
                }}
              >
                <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                  <Form.Item
                    name={[field.name, 'kind']}
                    label={t('deploygov.settings.verificationCheckKind')}
                    style={{ flex: 1 }}
                    rules={[{ required: true }]}
                  >
                    <Select options={kindOptions} />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'name']}
                    label={t('deploygov.settings.verificationCheckName')}
                    style={{ flex: 1 }}
                    rules={[
                      { required: true, whitespace: true, max: LIMITS.nameMax },
                      {
                        validator: async (_, value: string | undefined) => {
                          const checks = form.getFieldValue([...CHECKS]) as
                            | VerificationFormValues['checks']
                            | undefined;
                          if (isDuplicateCheckName(checks, index, value)) {
                            throw new Error(t('deploygov.settings.verificationCheckNameDuplicate'));
                          }
                        },
                      },
                    ]}
                  >
                    <Input maxLength={LIMITS.nameMax} />
                  </Form.Item>
                  <Button
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('deploygov.settings.verificationRemoveCheck')}
                    style={{ marginTop: 30 }}
                    onClick={() => remove(field.name)}
                  />
                </div>
                <Form.Item
                  noStyle
                  shouldUpdate={(prev: EnvironmentFormValues, cur: EnvironmentFormValues) =>
                    prev.verification?.checks?.[index]?.kind !==
                    cur.verification?.checks?.[index]?.kind
                  }
                >
                  {({ getFieldValue }) =>
                    getFieldValue([...CHECKS, field.name, 'kind']) === 'PROMQL_THRESHOLD' ? (
                      <>
                        <Form.Item
                          name={[field.name, 'prometheus_url']}
                          label={t('deploygov.settings.verificationPrometheusUrl')}
                          extra={t('deploygov.settings.verificationPrometheusUrlHelp')}
                          rules={[
                            { required: true, whitespace: true, max: LIMITS.urlMax },
                            httpUrlRule,
                          ]}
                        >
                          <Input placeholder="https://prometheus.example.com" />
                        </Form.Item>
                        <Form.Item
                          name={[field.name, 'query']}
                          label={t('deploygov.settings.verificationQuery')}
                          rules={[{ required: true, whitespace: true, max: LIMITS.queryMax }]}
                        >
                          <Input.TextArea rows={2} className="mono" />
                        </Form.Item>
                        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                          <Form.Item
                            name={[field.name, 'comparator']}
                            label={t('deploygov.settings.verificationComparator')}
                            style={{ flex: 1, minWidth: 120 }}
                            rules={[{ required: true }]}
                          >
                            <Select options={comparatorOptions} />
                          </Form.Item>
                          <Form.Item
                            name={[field.name, 'threshold']}
                            label={t('deploygov.settings.verificationValueThreshold')}
                            style={{ flex: 1, minWidth: 120 }}
                            rules={[{ required: true, type: 'number' }]}
                          >
                            <InputNumber style={{ width: '100%' }} />
                          </Form.Item>
                          <Form.Item
                            name={[field.name, 'no_data_is_failure']}
                            label={t('deploygov.settings.verificationNoDataIsFailure')}
                            valuePropName="checked"
                            style={{ flex: 1, minWidth: 160 }}
                          >
                            <Switch size="small" />
                          </Form.Item>
                        </div>
                      </>
                    ) : (
                      <>
                        <Form.Item
                          name={[field.name, 'url']}
                          label={t('deploygov.settings.verificationUrl')}
                          rules={[
                            { required: true, whitespace: true, max: LIMITS.urlMax },
                            httpUrlRule,
                          ]}
                        >
                          <Input placeholder="https://service.example.com/healthz" />
                        </Form.Item>
                        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                          <Form.Item
                            name={[field.name, 'expected_status']}
                            label={t('deploygov.settings.verificationExpectedStatus')}
                            style={{ flex: 1, minWidth: 120 }}
                            rules={[
                              {
                                type: 'number',
                                min: LIMITS.expectedStatusMin,
                                max: LIMITS.expectedStatusMax,
                                message: t('deploygov.settings.verificationExpectedStatusRange'),
                              },
                            ]}
                          >
                            <InputNumber
                              min={LIMITS.expectedStatusMin}
                              max={LIMITS.expectedStatusMax}
                              precision={0}
                              style={{ width: '100%' }}
                            />
                          </Form.Item>
                          <Form.Item
                            name={[field.name, 'body_contains']}
                            label={t('deploygov.settings.verificationBodyContains')}
                            style={{ flex: 2, minWidth: 200 }}
                            rules={[{ max: LIMITS.bodyContainsMax }]}
                          >
                            <Input />
                          </Form.Item>
                        </div>
                      </>
                    )
                  }
                </Form.Item>
              </div>
            ))}
            <Form.ErrorList errors={errors} />
            <div>
              <Button
                icon={<PlusOutlined />}
                disabled={fields.length >= LIMITS.maxChecks}
                onClick={() => add(defaultCheckValues())}
              >
                {t('deploygov.settings.verificationAddCheck')}
              </Button>
            </div>
          </div>
        )}
      </Form.List>
    </>
  );
}
