import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Checkbox,
  Collapse,
  Form,
  Input,
  InputNumber,
  Modal,
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
} from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  apiConnectorKeys,
  deleteApiSchema,
  getApiConnector,
  listApiOperations,
  listApiSchemas,
  previewApiSchemaFilter,
  updateApiConnector,
  updateApiSchemaFilter,
  uploadApiSchema,
} from '@/api/apiConnectors';
import { aiConfigKeys, listAiConfigs } from '@/api/admin';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import {
  API_AUTH_METHODS,
  API_SCHEMA_TYPES,
  aiProviderLabel,
  apiAuthMethodLabel,
  apiSchemaTypeLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { Oauth2ConnectorFields } from '@/components/apigov/Oauth2ConnectorFields';
import { KeyValueEditor } from '@/components/apigov/KeyValueEditor';
import { ApiConnectorMaskingTab } from '@/components/apigov/ApiConnectorMaskingTab';
import { ApiConnectorClassificationTab } from '@/components/apigov/ApiConnectorClassificationTab';
import { ApiConnectorPermissionsTab } from '@/components/apigov/ApiConnectorPermissionsTab';
import { ApiConnectorVariablesTab } from '@/components/apigov/ApiConnectorVariablesTab';
import {
  pairsToRecord,
  recordToPairs,
  type KeyValuePair,
} from '@/utils/apiRequestComposition';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  ApiAuthMethod,
  ApiOperation,
  ApiOperationFilter,
  ApiSchema,
  ApiSchemaFilterPreview,
  ApiSchemaType,
  Oauth2GrantType,
  UpdateApiConnectorInput,
} from '@/types/api';

export default function ApiConnectorSettingsPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const connectorQuery = useQuery({
    queryKey: apiConnectorKeys.detail(id),
    queryFn: () => getApiConnector(id),
    enabled: !!id,
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-api-connectors"
        title={connectorQuery.data?.name ?? t('apiGov.settings.title')}
        subtitle={connectorQuery.data?.base_url}
        actions={<Button onClick={() => navigate('/api-connectors')}>{t('common.back')}</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        <Tabs
          items={[
            { key: 'config', label: t('apiGov.settings.tabConfig'), children: <ConfigTab connectorId={id} /> },
            { key: 'schema', label: t('apiGov.settings.tabSchema'), children: <SchemaTab connectorId={id} /> },
            { key: 'operations', label: t('apiGov.settings.tabOperations'), children: <OperationsTab connectorId={id} /> },
            { key: 'permissions', label: t('apiGov.settings.tabPermissions'), children: <ApiConnectorPermissionsTab connectorId={id} /> },
            { key: 'variables', label: t('apiGov.settings.tabVariables'), children: <ApiConnectorVariablesTab connectorId={id} /> },
            { key: 'masking', label: t('apiGov.settings.tabMasking'), children: <ApiConnectorMaskingTab connectorId={id} /> },
            { key: 'classification', label: t('apiGov.settings.tabClassification'), children: <ApiConnectorClassificationTab connectorId={id} /> },
          ]}
        />
      </div>
    </div>
  );
}

function ConfigTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm();
  const authMethod = Form.useWatch('auth_method', form) as ApiAuthMethod | undefined;
  const grantType = Form.useWatch('oauth2_grant_type', form) as Oauth2GrantType | undefined;
  const aiAnalysisEnabled = Form.useWatch('ai_analysis_enabled', form) as boolean | undefined;
  const textToApiEnabled = Form.useWatch('text_to_api_enabled', form) as boolean | undefined;
  const aiRequired = aiAnalysisEnabled === true || textToApiEnabled === true;
  const connectorQuery = useQuery({
    queryKey: apiConnectorKeys.detail(connectorId),
    queryFn: () => getApiConnector(connectorId),
  });
  const [headers, setHeaders] = useState<KeyValuePair[]>([]);
  const [traceMapping, setTraceMapping] = useState<KeyValuePair[]>([]);
  useEffect(() => {
    if (connectorQuery.data) {
      setHeaders(recordToPairs(connectorQuery.data.default_headers));
      setTraceMapping(recordToPairs(connectorQuery.data.trace_header_mapping));
    }
  }, [connectorQuery.data]);
  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: listAiConfigs,
  });
  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: listReviewPlans,
  });
  const mutation = useMutation({
    mutationFn: (input: UpdateApiConnectorInput) => updateApiConnector(connectorId, input),
    onSuccess: () => {
      message.success(t('apiGov.connectors.updated'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connectorId) });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  if (!connectorQuery.data) return null;
  const c = connectorQuery.data;
  return (
    <Form
      form={form}
      layout="vertical"
      style={{ maxWidth: 560 }}
      initialValues={{
        name: c.name,
        base_url: c.base_url,
        timeout_ms: c.timeout_ms,
        max_response_bytes: c.max_response_bytes,
        auth_method: c.auth_method,
        oauth2_token_uri: c.oauth2_token_uri ?? undefined,
        oauth2_client_id: c.oauth2_client_id ?? undefined,
        oauth2_scopes: c.oauth2_scopes ?? undefined,
        oauth2_audience: c.oauth2_audience ?? undefined,
        oauth2_username: c.oauth2_username ?? undefined,
        oauth2_grant_type: c.oauth2_grant_type,
        oauth2_client_auth: c.oauth2_client_auth,
        ai_analysis_enabled: c.ai_analysis_enabled,
        ai_config_id: c.ai_config_id ?? null,
        review_plan_id: c.review_plan_id ?? null,
        text_to_api_enabled: c.text_to_api_enabled,
        require_review_reads: c.require_review_reads,
        require_review_writes: c.require_review_writes,
        active: c.active,
      }}
      onFinish={(values) =>
        mutation.mutate({
          ...values,
          default_headers: pairsToRecord(headers),
          trace_header_mapping: pairsToRecord(traceMapping),
          // review_plan_id: null means "unchanged" on the update endpoint, so an
          // explicit clear rides the dedicated flag (same as datasource clear_ai_config).
          clear_review_plan: values.review_plan_id == null,
        })
      }
    >
      <Form.Item name="name" label={t('apiGov.connectors.name')} rules={[{ required: true, min: 3, max: 255 }]}>
        <Input />
      </Form.Item>
      <Form.Item name="base_url" label={t('apiGov.connectors.baseUrl')} rules={[{ required: true, max: 2048 }]}>
        <Input />
      </Form.Item>
      <Form.Item name="auth_method" label={t('apiGov.connectors.authMethod')}>
        <Select options={enumOptions(API_AUTH_METHODS, apiAuthMethodLabel, t)} />
      </Form.Item>
      {authMethod === 'OAUTH2_CLIENT_CREDENTIALS' && (
        <Oauth2ConnectorFields
          grantType={grantType ?? c.oauth2_grant_type}
          clientSecretConfigured={c.oauth2_client_secret_configured}
          refreshTokenConfigured={c.oauth2_refresh_token_configured}
          passwordConfigured={c.oauth2_password_configured}
        />
      )}
      <Form.Item label={t('apiGov.settings.defaultHeaders')} extra={t('apiGov.settings.defaultHeadersHint')}>
        <KeyValueEditor pairs={headers} onChange={setHeaders} />
      </Form.Item>
      <Form.Item label={t('apiGov.settings.traceHeaderMapping')} extra={t('apiGov.settings.traceHeaderMappingHint')}>
        <KeyValueEditor pairs={traceMapping} onChange={setTraceMapping} />
      </Form.Item>
      <Form.Item name="timeout_ms" label={t('apiGov.connectors.timeoutMs')} rules={[{ type: 'number', min: 1 }]}>
        <InputNumber style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="max_response_bytes" label={t('apiGov.connectors.maxResponseBytes')} rules={[{ type: 'number', min: 1 }]}>
        <InputNumber style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="ai_analysis_enabled" label={t('apiGov.connectors.aiAnalysis')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name="text_to_api_enabled" label={t('apiGov.connectors.textToApi')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item
        name="ai_config_id"
        label={t('apiGov.connectors.aiConfig')}
        dependencies={['ai_analysis_enabled', 'text_to_api_enabled']}
        rules={[{ required: aiRequired, message: t('apiGov.connectors.aiConfigRequired') }]}
        extra={
          <a href="/admin/ai-configs/new" target="_blank" rel="noopener noreferrer">
            {t('apiGov.connectors.addAiConfig')}
          </a>
        }
      >
        <Select
          allowClear
          disabled={!aiRequired}
          placeholder={t('apiGov.connectors.aiConfigPlaceholder')}
          options={(aiConfigsQuery.data ?? []).map((cfg) => ({
            value: cfg.id,
            label: `${cfg.name} · ${aiProviderLabel(t, cfg.provider)}`,
          }))}
        />
      </Form.Item>
      <Form.Item
        name="review_plan_id"
        label={t('apiGov.connectors.reviewPlan')}
        extra={t('apiGov.connectors.reviewPlanHint')}
      >
        <Select
          allowClear
          loading={reviewPlansQuery.isLoading}
          placeholder={
            reviewPlansQuery.isLoading
              ? t('apiGov.connectors.reviewPlanLoading')
              : t('apiGov.connectors.reviewPlanPlaceholder')
          }
          options={(reviewPlansQuery.data ?? []).map((plan) => ({
            value: plan.id,
            label: plan.name,
          }))}
        />
      </Form.Item>
      <Form.Item name="require_review_reads" label={t('apiGov.connectors.requireReviewReads')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name="require_review_writes" label={t('apiGov.connectors.requireReviewWrites')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Form.Item name="active" label={t('apiGov.connectors.active')} valuePropName="checked">
        <Switch />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={mutation.isPending}>
        {t('common.save')}
      </Button>
    </Form>
  );
}

const HTTP_VERBS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];
const MAX_FILTER_LIST = 100;
const MAX_FILTER_PATTERN = 200;

const EMPTY_FILTER: ApiOperationFilter = {
  includePaths: [],
  excludePaths: [],
  includeVerbs: [],
  excludeVerbs: [],
  includeOperationIds: [],
  excludeOperationIds: [],
  includeTags: [],
  excludeTags: [],
  excludeDeprecated: false,
};

function filterIsEmpty(f: ApiOperationFilter): boolean {
  return (
    !f.excludeDeprecated &&
    !f.includePaths?.length &&
    !f.excludePaths?.length &&
    !f.includeVerbs?.length &&
    !f.excludeVerbs?.length &&
    !f.includeOperationIds?.length &&
    !f.excludeOperationIds?.length &&
    !f.includeTags?.length &&
    !f.excludeTags?.length
  );
}

/** Returns a validation-error i18n key when the filter breaks the size caps, else null. */
function filterViolation(f: ApiOperationFilter): string | null {
  const lists = [
    f.includePaths,
    f.excludePaths,
    f.includeVerbs,
    f.excludeVerbs,
    f.includeOperationIds,
    f.excludeOperationIds,
    f.includeTags,
    f.excludeTags,
  ];
  for (const list of lists) {
    if ((list?.length ?? 0) > MAX_FILTER_LIST) return 'apiGov.settings.filterTooManyPatterns';
    if (list?.some((v) => v.length > MAX_FILTER_PATTERN)) return 'apiGov.settings.filterPatternTooLong';
  }
  return null;
}

interface FilterFieldsProps {
  value: ApiOperationFilter;
  onChange: (next: ApiOperationFilter) => void;
}

function OperationFilterFields({ value, onChange }: FilterFieldsProps) {
  const { t } = useTranslation();
  const set = (patch: Partial<ApiOperationFilter>) => onChange({ ...value, ...patch });
  const tagsInput = (
    field: keyof ApiOperationFilter,
    label: string,
    placeholder?: string,
  ) => (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span>{label}</span>
      <Select<string[]>
        mode="tags"
        allowClear
        tokenSeparators={[',', ' ']}
        open={false}
        suffixIcon={null}
        value={(value[field] as string[] | undefined) ?? []}
        onChange={(v) => set({ [field]: v } as Partial<ApiOperationFilter>)}
        placeholder={placeholder}
        aria-label={label}
      />
    </label>
  );
  return (
    <Space orientation="vertical" style={{ width: '100%' }} size={12}>
      {tagsInput('excludePaths', t('apiGov.settings.filterExcludePaths'), t('apiGov.settings.filterPathPlaceholder'))}
      {tagsInput('includePaths', t('apiGov.settings.filterIncludePaths'), t('apiGov.settings.filterPathPlaceholder'))}
      <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span>{t('apiGov.settings.filterExcludeVerbs')}</span>
        <Select<string[]>
          mode="multiple"
          allowClear
          value={value.excludeVerbs ?? []}
          onChange={(v) => set({ excludeVerbs: v })}
          options={HTTP_VERBS.map((v) => ({ value: v, label: v }))}
          aria-label={t('apiGov.settings.filterExcludeVerbs')}
        />
      </label>
      {tagsInput('excludeOperationIds', t('apiGov.settings.filterExcludeOperationIds'), t('apiGov.settings.filterOperationIdPlaceholder'))}
      {tagsInput('excludeTags', t('apiGov.settings.filterExcludeTags'), t('apiGov.settings.filterTagPlaceholder'))}
      <Checkbox
        checked={value.excludeDeprecated ?? false}
        onChange={(e) => set({ excludeDeprecated: e.target.checked })}
      >
        {t('apiGov.settings.filterExcludeDeprecated')}
      </Checkbox>
    </Space>
  );
}

function EditFilterModal({
  connectorId,
  schema,
  onClose,
}: {
  connectorId: string;
  schema: ApiSchema | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<ApiOperationFilter>(EMPTY_FILTER);
  useEffect(() => {
    setFilter({ ...EMPTY_FILTER, ...(schema?.operation_filter ?? {}) });
  }, [schema]);
  const mutation = useMutation({
    mutationFn: () => updateApiSchemaFilter(connectorId, schema!.id, filter),
    onSuccess: () => {
      message.success(t('apiGov.settings.filterUpdated'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.schemas(connectorId) });
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.operations(connectorId) });
      onClose();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  const submit = () => {
    const violation = filterViolation(filter);
    if (violation) {
      message.error(t(violation));
      return;
    }
    mutation.mutate();
  };
  return (
    <Modal
      open={schema !== null}
      title={t('apiGov.settings.filterEdit')}
      onCancel={onClose}
      onOk={submit}
      confirmLoading={mutation.isPending}
      okText={t('common.save')}
      destroyOnHidden
    >
      <OperationFilterFields value={filter} onChange={setFilter} />
    </Modal>
  );
}

function SchemaTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [schemaType, setSchemaType] = useState<ApiSchemaType>('OPENAPI');
  const [source, setSource] = useState<'paste' | 'upload' | 'url'>('paste');
  const [content, setContent] = useState('');
  const [fileName, setFileName] = useState('');
  const [sourceUrl, setSourceUrl] = useState('');
  const [filter, setFilter] = useState<ApiOperationFilter>(EMPTY_FILTER);
  const [preview, setPreview] = useState<ApiSchemaFilterPreview | null>(null);
  const [imported, setImported] = useState<{ kept: number; total: number } | null>(null);
  const [editing, setEditing] = useState<ApiSchema | null>(null);
  const schemasQuery = useQuery({
    queryKey: apiConnectorKeys.schemas(connectorId),
    queryFn: () => listApiSchemas(connectorId),
  });
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.schemas(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.operations(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connectorId) });
  };
  const payload = () => ({
    schema_type: schemaType,
    raw_content: source === 'url' ? '' : content,
    source_url: source === 'url' ? sourceUrl.trim() : null,
    filter: filterIsEmpty(filter) ? null : filter,
  });
  const uploadMutation = useMutation({
    mutationFn: () => uploadApiSchema(connectorId, payload()),
    onSuccess: (schema) => {
      message.success(t('apiGov.settings.uploaded'));
      setImported({ kept: schema.operation_count, total: schema.total_operation_count });
      setContent('');
      setFileName('');
      setSourceUrl('');
      setPreview(null);
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  const previewMutation = useMutation({
    mutationFn: () => previewApiSchemaFilter(connectorId, payload()),
    onSuccess: (result) => setPreview(result),
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  const runUpload = () => {
    const violation = filterViolation(filter);
    if (violation) {
      message.error(t(violation));
      return;
    }
    uploadMutation.mutate();
  };
  const runPreview = () => {
    const violation = filterViolation(filter);
    if (violation) {
      message.error(t(violation));
      return;
    }
    previewMutation.mutate();
  };
  const canUpload =
    source === 'url' ? !!sourceUrl.trim() : source === 'upload' ? !!content : !!content.trim();
  const deleteMutation = useMutation({
    mutationFn: (schemaId: string) => deleteApiSchema(connectorId, schemaId),
    onSuccess: () => {
      message.success(t('apiGov.settings.schemaDeleted'));
      invalidate();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 700 }}>
      <Select<ApiSchemaType>
        style={{ width: 240 }}
        value={schemaType}
        onChange={setSchemaType}
        options={enumOptions(API_SCHEMA_TYPES, apiSchemaTypeLabel, t)}
      />
      <Segmented
        value={source}
        onChange={(v) => setSource(v as 'paste' | 'upload' | 'url')}
        options={[
          { value: 'paste', label: t('apiGov.settings.schemaSourcePaste') },
          { value: 'upload', label: t('apiGov.settings.schemaSourceUpload') },
          { value: 'url', label: t('apiGov.settings.schemaSourceUrl') },
        ]}
      />
      {source === 'paste' && (
        <Input.TextArea
          rows={8}
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder={t('apiGov.settings.schemaContent')}
        />
      )}
      {source === 'upload' && (
        <Upload
          maxCount={1}
          showUploadList={false}
          accept=".json,.yaml,.yml,.wsdl,.xml,.graphql,.graphqls,.proto"
          beforeUpload={(file) => {
            void file.text().then((text) => {
              setContent(text);
              setFileName(file.name);
            });
            return false;
          }}
        >
          <Button icon={<UploadOutlined />}>{fileName || t('apiGov.settings.schemaFile')}</Button>
        </Upload>
      )}
      {source === 'url' && (
        <Input
          value={sourceUrl}
          onChange={(e) => setSourceUrl(e.target.value)}
          placeholder={t('apiGov.settings.schemaUrlPlaceholder')}
          aria-label={t('apiGov.settings.schemaUrl')}
        />
      )}
      <Collapse
        ghost
        items={[
          {
            key: 'filter',
            label: t('apiGov.settings.filterSection'),
            children: <OperationFilterFields value={filter} onChange={setFilter} />,
          },
        ]}
      />
      {preview && (
        <div style={{ borderLeft: '3px solid var(--af-color-primary, #1677ff)', paddingLeft: 12 }}>
          <Typography.Text strong>
            {t('apiGov.settings.filterKeepsOf', { kept: preview.kept_count, total: preview.total_count })}
          </Typography.Text>
          {preview.excluded.length > 0 && (
            <Table<ApiOperation>
              rowKey="operation_id"
              size="small"
              pagination={false}
              style={{ marginTop: 8 }}
              dataSource={preview.excluded}
              columns={[
                { title: t('apiGov.settings.operationId'), dataIndex: 'operation_id' },
                { title: t('apiGov.settings.verb'), dataIndex: 'verb' },
                { title: t('apiGov.settings.path'), dataIndex: 'path' },
              ]}
            />
          )}
        </div>
      )}
      {imported && (
        <Typography.Text type="secondary">
          {t('apiGov.settings.filterImportedOf', { kept: imported.kept, total: imported.total })}
        </Typography.Text>
      )}
      <Space>
        <Button
          type="primary"
          disabled={!canUpload}
          loading={uploadMutation.isPending}
          onClick={runUpload}
        >
          {t('apiGov.settings.uploadSchema')}
        </Button>
        <Button disabled={!canUpload} loading={previewMutation.isPending} onClick={runPreview}>
          {t('apiGov.settings.filterPreview')}
        </Button>
      </Space>
      <Table<ApiSchema>
        rowKey="id"
        size="small"
        pagination={false}
        loading={schemasQuery.isLoading}
        dataSource={schemasQuery.data ?? []}
        columns={[
          { title: t('apiGov.settings.schemaType'), dataIndex: 'schema_type', render: (s: ApiSchemaType) => apiSchemaTypeLabel(t, s) },
          {
            title: t('apiGov.settings.operations'),
            key: 'ops',
            render: (_: unknown, row: ApiSchema) =>
              row.operation_filter
                ? t('apiGov.settings.filterKeepsOf', { kept: row.operation_count, total: row.total_operation_count })
                : row.operation_count,
          },
          { title: '', key: 'a', render: (_: unknown, row: ApiSchema) => (
            <Space>
              <Button size="small" onClick={() => setEditing(row)}>
                {t('apiGov.settings.filterEdit')}
              </Button>
              <Button size="small" danger loading={deleteMutation.isPending} onClick={() => deleteMutation.mutate(row.id)}>
                {t('common.delete')}
              </Button>
            </Space>
          ) },
        ]}
      />
      <EditFilterModal connectorId={connectorId} schema={editing} onClose={() => setEditing(null)} />
    </div>
  );
}

function OperationsTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const opsQuery = useQuery({
    queryKey: apiConnectorKeys.operations(connectorId),
    queryFn: () => listApiOperations(connectorId),
  });
  return (
    <Table<ApiOperation>
      rowKey="operation_id"
      size="small"
      pagination={false}
      loading={opsQuery.isLoading}
      dataSource={opsQuery.data ?? []}
      locale={{ emptyText: t('apiGov.settings.noOperations') }}
      columns={[
        { title: t('apiGov.settings.operationId'), dataIndex: 'operation_id' },
        { title: t('apiGov.settings.verb'), dataIndex: 'verb' },
        { title: t('apiGov.settings.path'), dataIndex: 'path' },
        {
          title: t('apiGov.settings.readWrite'),
          dataIndex: 'write',
          render: (write: boolean) =>
            write ? <Tag color="orange">{t('apiGov.settings.write')}</Tag> : <Tag color="green">{t('apiGov.settings.read')}</Tag>,
        },
      ]}
    />
  );
}
