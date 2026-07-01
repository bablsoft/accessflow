import { useEffect, useMemo, useState } from 'react';
import { App, Button, Form, Input, InputNumber, Segmented, Select, Switch, Table, Tabs, Tag, Upload } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  apiConnectorKeys,
  deleteApiSchema,
  getApiConnector,
  grantApiConnectorPermission,
  listApiConnectorPermissions,
  listApiOperations,
  listApiSchemas,
  revokeApiConnectorPermission,
  updateApiConnector,
  uploadApiSchema,
} from '@/api/apiConnectors';
import { aiConfigKeys, listAiConfigs, listUsers, userKeys } from '@/api/admin';
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
import {
  pairsToRecord,
  recordToPairs,
  type KeyValuePair,
} from '@/utils/apiRequestComposition';
import type {
  ApiAuthMethod,
  ApiConnectorPermission,
  ApiOperation,
  ApiSchema,
  ApiSchemaType,
  Oauth2GrantType,
  UpdateApiConnectorInput,
  User,
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
            { key: 'permissions', label: t('apiGov.settings.tabPermissions'), children: <PermissionsTab connectorId={id} /> },
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
  const mutation = useMutation({
    mutationFn: (input: UpdateApiConnectorInput) => updateApiConnector(connectorId, input),
    onSuccess: () => {
      message.success(t('apiGov.connectors.updated'));
      queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connectorId) });
    },
    onError: () => message.error(t('apiGov.error')),
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

function SchemaTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [schemaType, setSchemaType] = useState<ApiSchemaType>('OPENAPI');
  const [source, setSource] = useState<'paste' | 'upload' | 'url'>('paste');
  const [content, setContent] = useState('');
  const [fileName, setFileName] = useState('');
  const [sourceUrl, setSourceUrl] = useState('');
  const schemasQuery = useQuery({
    queryKey: apiConnectorKeys.schemas(connectorId),
    queryFn: () => listApiSchemas(connectorId),
  });
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.schemas(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.operations(connectorId) });
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.detail(connectorId) });
  };
  const uploadMutation = useMutation({
    mutationFn: () =>
      uploadApiSchema(connectorId, {
        schema_type: schemaType,
        raw_content: source === 'url' ? '' : content,
        source_url: source === 'url' ? sourceUrl.trim() : null,
      }),
    onSuccess: () => {
      message.success(t('apiGov.settings.uploaded'));
      setContent('');
      setFileName('');
      setSourceUrl('');
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });
  const canUpload =
    source === 'url' ? !!sourceUrl.trim() : source === 'upload' ? !!content : !!content.trim();
  const deleteMutation = useMutation({
    mutationFn: (schemaId: string) => deleteApiSchema(connectorId, schemaId),
    onSuccess: () => {
      message.success(t('apiGov.settings.schemaDeleted'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
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
      <Button
        type="primary"
        style={{ alignSelf: 'flex-start' }}
        disabled={!canUpload}
        loading={uploadMutation.isPending}
        onClick={() => uploadMutation.mutate()}
      >
        {t('apiGov.settings.uploadSchema')}
      </Button>
      <Table<ApiSchema>
        rowKey="id"
        size="small"
        pagination={false}
        loading={schemasQuery.isLoading}
        dataSource={schemasQuery.data ?? []}
        columns={[
          { title: t('apiGov.settings.schemaType'), dataIndex: 'schema_type', render: (s: ApiSchemaType) => apiSchemaTypeLabel(t, s) },
          { title: t('apiGov.settings.operations'), dataIndex: 'operation_count' },
          { title: '', key: 'a', render: (_: unknown, row: ApiSchema) => (
            <Button size="small" danger loading={deleteMutation.isPending} onClick={() => deleteMutation.mutate(row.id)}>
              {t('common.delete')}
            </Button>
          ) },
        ]}
      />
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

function PermissionsTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<{ user_id: string; can_read: boolean; can_write: boolean; can_break_glass: boolean }>();
  const permsQuery = useQuery({
    queryKey: apiConnectorKeys.permissions(connectorId),
    queryFn: () => listApiConnectorPermissions(connectorId),
  });
  const usersQuery = useQuery({
    queryKey: userKeys.list({ size: 100 }),
    queryFn: () => listUsers({ size: 100 }),
  });
  const taken = useMemo(
    () => new Set((permsQuery.data ?? []).map((p) => p.user_id)),
    [permsQuery.data],
  );
  const eligible: User[] = useMemo(
    () => (usersQuery.data?.content ?? []).filter((u) => u.active && !taken.has(u.id)),
    [usersQuery.data, taken],
  );
  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: apiConnectorKeys.permissions(connectorId) });
  const grantMutation = useMutation({
    mutationFn: (values: { user_id: string; can_read: boolean; can_write: boolean; can_break_glass: boolean }) =>
      grantApiConnectorPermission(connectorId, values),
    onSuccess: () => {
      message.success(t('apiGov.settings.granted'));
      form.resetFields();
      invalidate();
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
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 760 }}>
      <Form
        form={form}
        layout="inline"
        initialValues={{ can_read: true, can_write: false, can_break_glass: false }}
        onFinish={(values) => grantMutation.mutate(values)}
      >
        <Form.Item name="user_id" rules={[{ required: true, message: t('apiGov.settings.userRequired') }]}>
          <Select<string>
            showSearch
            optionFilterProp="label"
            placeholder={t('apiGov.settings.userPlaceholder')}
            style={{ width: 280 }}
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
        <Form.Item name="can_read" label={t('apiGov.settings.canRead')} valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="can_write" label={t('apiGov.settings.canWrite')} valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
        <Form.Item name="can_break_glass" label={t('apiGov.settings.canBreakGlass')} valuePropName="checked">
          <Switch size="small" />
        </Form.Item>
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
          { title: '', key: 'a', render: (_: unknown, row: ApiConnectorPermission) => (
            <Button size="small" danger loading={revokeMutation.isPending} onClick={() => revokeMutation.mutate(row.id)}>
              {t('apiGov.settings.revoke')}
            </Button>
          ) },
        ]}
      />
    </div>
  );
}
