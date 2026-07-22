import { useCallback, useMemo, useState } from 'react';
import { Alert, App, Button, Form, Input, InputNumber, Radio, Select, Switch } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createDatasource,
  datasourceKeys,
  getDatasourceTypes,
  testConnection,
  updateDatasource,
} from '@/api/datasources';
import { aiConfigKeys, listAiConfigs, setupProgressKeys } from '@/api/admin';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { datasourceCreateErrorMessage } from '@/utils/apiErrors';
import { aiProviderLabel, enumOptions, sslModeLabel } from '@/utils/enumLabels';
import { secretReferenceHelp, secretReferenceRule } from '@/utils/secretReference';
import { useSecretProviders } from '@/hooks/useSecretProviders';
import { showApiError } from '@/utils/showApiError';
import type {
  ConnectionTestResult,
  CreateDatasourceInput,
  Datasource,
  DatasourceTypeOption,
  SslMode,
  UpdateDatasourceInput,
} from '@/types/api';
import { DatasourceTypeSelector, optionKey } from '@/components/datasources/DatasourceTypeSelector';
import {
  DatasourceWizardSteps,
  type WizardStepKey,
} from '@/components/datasources/DatasourceWizardSteps';
import { JdbcUrlPreview } from '@/components/datasources/JdbcUrlPreview';
import { ConnectionTester } from '@/components/datasources/ConnectionTester';

type AuthMethod = 'basic' | 'api_key';

interface ConnectionFormValues {
  name: string;
  host: string;
  port: number;
  database_name: string;
  jdbc_url: string;
  dynamodb_endpoint: string;
  neo4j_bolt_uri: string;
  snowflake_url_override: string;
  bigquery_endpoint: string;
  databricks_http_path: string;
  local_datacenter: string;
  auth_method: AuthMethod;
  api_key: string;
  username: string;
  password: string;
  ssl_mode: SslMode;
}

const SEARCH_ENGINES = ['ELASTICSEARCH', 'OPENSEARCH'];

interface SettingsFormValues {
  connection_pool_size: number;
  max_rows_per_query: number;
  review_plan_id: string | null;
  require_review_reads: boolean;
  require_review_writes: boolean;
  ai_analysis_enabled: boolean;
  ai_config_id: string | null;
  text_to_sql_enabled: boolean;
}

const SSL_MODES: SslMode[] = ['DISABLE', 'REQUIRE', 'VERIFY_CA', 'VERIFY_FULL'];

export default function DatasourceCreateWizardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [currentStep, setCurrentStep] = useState<WizardStepKey>('type');
  const [selectedType, setSelectedType] = useState<DatasourceTypeOption | null>(null);
  const [connectionForm] = Form.useForm<ConnectionFormValues>();
  const [settingsForm] = Form.useForm<SettingsFormValues>();
  const [createdDatasource, setCreatedDatasource] = useState<Datasource | null>(null);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  const typesQuery = useQuery({
    queryKey: datasourceKeys.types(),
    queryFn: getDatasourceTypes,
  });

  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: listAiConfigs,
  });

  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: listReviewPlans,
  });

  const secretProviders = useSecretProviders();
  const secretRefHelp = secretReferenceHelp(secretProviders, t);
  const secretRefRule = secretReferenceRule(secretProviders, t);

  const connectionValues = Form.useWatch([], connectionForm);
  const settingsValues = Form.useWatch([], settingsForm);
  const previewedTemplate = selectedType?.jdbc_url_template ?? '';

  const handleSelectType = useCallback(
    (option: DatasourceTypeOption) => {
      setSelectedType(option);
      connectionForm.setFieldsValue({
        port: option.default_port,
        ssl_mode: option.default_ssl_mode,
      });
      setCurrentStep('connection');
    },
    [connectionForm],
  );

  // CUSTOM via a connector uses host/port/database (URL built from the connector template);
  // only an uploaded/bare CUSTOM driver needs the free-form JDBC URL field.
  const dynamicMode =
    selectedType?.code === 'CUSTOM' && selectedType?.source !== 'connector';

  const persistConnection = useMutation({
    mutationFn: async (values: ConnectionFormValues) => {
      if (!selectedType) {
        throw new Error('No datasource type selected');
      }
      const isDynamic =
        selectedType.code === 'CUSTOM' && selectedType.source !== 'connector';
      const isCqlEngine =
        selectedType.code === 'CASSANDRA' || selectedType.code === 'SCYLLADB';
      const isDynamoDb = selectedType.code === 'DYNAMODB';
      const isNeo4j = selectedType.code === 'NEO4J';
      const isSnowflake = selectedType.code === 'SNOWFLAKE';
      const isBigQuery = selectedType.code === 'BIGQUERY';
      const isDatabricks = selectedType.code === 'DATABRICKS';
      const isSearchEngine = SEARCH_ENGINES.includes(selectedType.code);
      const useApiKey = isSearchEngine && values.auth_method === 'api_key';
      // API-key auth replaces basic creds; send blank username/password (the backend keeps the
      // NOT NULL columns satisfied) and the encrypted-at-rest API key. BigQuery (service-account
      // JSON) and Databricks (PAT) have no username either.
      const username = useApiKey || isBigQuery || isDatabricks ? '' : values.username;
      const password = useApiKey ? '' : values.password;
      if (createdDatasource) {
        const input: UpdateDatasourceInput = {
          name: values.name,
          username,
          password,
          ssl_mode: values.ssl_mode,
        };
        if (isDynamic) {
          input.jdbc_url_override = values.jdbc_url;
        } else if (isDynamoDb) {
          // Cloud-credentials model: database_name is the AWS region; the optional custom endpoint
          // (DynamoDB Local / VPC) rides on jdbc_url_override. Host/port are unused.
          input.database_name = values.database_name;
          input.jdbc_url_override = values.dynamodb_endpoint || '';
        } else if (isBigQuery) {
          // Cloud-credentials model: database_name is the GCP project (optionally project.dataset)
          // and the optional emulator endpoint rides on jdbc_url_override. Host/port are unused.
          input.database_name = values.database_name;
          input.jdbc_url_override = values.bigquery_endpoint || '';
        } else if (isSnowflake || isDatabricks) {
          // Warehouse hosts are HTTPS endpoints — port is always 443 and never sent.
          input.host = values.host;
          input.database_name = values.database_name;
          input.jdbc_url_override = isSnowflake
            ? values.snowflake_url_override || ''
            : values.databricks_http_path;
        } else {
          input.host = values.host;
          input.port = values.port;
          input.database_name = values.database_name;
        }
        if (isCqlEngine) {
          input.local_datacenter = values.local_datacenter;
        }
        if (isNeo4j) {
          // Optional full bolt:// / neo4j+s:// URI (Aura / clustered routing) overrides host/port.
          input.jdbc_url_override = values.neo4j_bolt_uri || '';
        }
        if (isSearchEngine) {
          input.api_key = useApiKey ? values.api_key : '';
        }
        return updateDatasource(createdDatasource.id, input);
      }
      const input: CreateDatasourceInput = {
        name: values.name,
        db_type: selectedType.code,
        username,
        password,
        ssl_mode: values.ssl_mode,
        ai_analysis_enabled: false,
        ai_config_id: null,
        custom_driver_id: selectedType.custom_driver_id ?? null,
        connector_id: selectedType.connector_id ?? null,
      };
      if (isDynamic) {
        input.jdbc_url_override = values.jdbc_url;
      } else if (isDynamoDb) {
        // Cloud-credentials model: database_name is the AWS region; the optional custom endpoint
        // (DynamoDB Local / VPC) rides on jdbc_url_override. Host/port are unused.
        input.database_name = values.database_name;
        if (values.dynamodb_endpoint) {
          input.jdbc_url_override = values.dynamodb_endpoint;
        }
      } else if (isBigQuery) {
        // Cloud-credentials model: database_name is the GCP project (optionally project.dataset)
        // and the optional emulator endpoint rides on jdbc_url_override. Host/port are unused.
        input.database_name = values.database_name;
        if (values.bigquery_endpoint) {
          input.jdbc_url_override = values.bigquery_endpoint;
        }
      } else if (isSnowflake || isDatabricks) {
        // Warehouse hosts are HTTPS endpoints — port is always 443 and never sent. Snowflake's
        // override (warehouse/role/schema params) is optional; Databricks' warehouse HTTP path
        // is required.
        input.host = values.host;
        input.database_name = values.database_name;
        if (isDatabricks) {
          input.jdbc_url_override = values.databricks_http_path;
        } else if (values.snowflake_url_override) {
          input.jdbc_url_override = values.snowflake_url_override;
        }
      } else {
        input.host = values.host;
        input.port = values.port;
        input.database_name = values.database_name;
      }
      if (isCqlEngine) {
        input.local_datacenter = values.local_datacenter;
      }
      if (isNeo4j && values.neo4j_bolt_uri) {
        // Optional full bolt:// / neo4j+s:// URI (Aura / clustered routing) overrides host/port.
        input.jdbc_url_override = values.neo4j_bolt_uri;
      }
      if (useApiKey) {
        input.api_key = values.api_key;
      }
      return createDatasource(input);
    },
    onSuccess: (saved) => {
      setCreatedDatasource(saved);
      queryClient.setQueryData(datasourceKeys.detail(saved.id), saved);
      queryClient.invalidateQueries({ queryKey: datasourceKeys.lists() });
      queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      setTestResult(null);
      setTestError(null);
      setCurrentStep('test');
    },
    onError: (err: unknown) => {
      const fallback = t('datasources.create.save_error');
      showApiError(message, err, (e) =>
        datasourceCreateErrorMessage(e) ?? extractDetail(e) ?? fallback,
      );
    },
  });

  const testMutation = useMutation({
    mutationFn: async () => {
      if (!createdDatasource) {
        throw new Error('No persisted datasource id');
      }
      return testConnection(createdDatasource.id);
    },
    onMutate: () => {
      setTestResult(null);
      setTestError(null);
    },
    onSuccess: (result) => {
      setTestResult(result);
    },
    onError: (err: unknown) => {
      const mapped = datasourceCreateErrorMessage(err);
      setTestError(mapped ?? extractDetail(err) ?? t('datasources.create.test_failure_unknown'));
    },
  });

  const persistSettings = useMutation({
    mutationFn: async (values: SettingsFormValues) => {
      if (!createdDatasource) {
        throw new Error('No persisted datasource id');
      }
      const input: UpdateDatasourceInput = {
        connection_pool_size: values.connection_pool_size,
        max_rows_per_query: values.max_rows_per_query,
        review_plan_id: values.review_plan_id ?? null,
        require_review_reads: values.require_review_reads,
        require_review_writes: values.require_review_writes,
        ai_analysis_enabled: values.ai_analysis_enabled,
        text_to_sql_enabled: values.text_to_sql_enabled,
        ai_config_id:
          values.ai_analysis_enabled || values.text_to_sql_enabled ? values.ai_config_id : null,
      };
      if (!values.ai_analysis_enabled && !values.text_to_sql_enabled) {
        input.clear_ai_config = true;
      }
      return updateDatasource(createdDatasource.id, input);
    },
    onSuccess: (saved) => {
      setCreatedDatasource(saved);
      queryClient.setQueryData(datasourceKeys.detail(saved.id), saved);
      queryClient.invalidateQueries({ queryKey: datasourceKeys.lists() });
      message.success(t('datasources.create.save_success'));
      navigate(`/datasources/${saved.id}/settings`);
    },
    onError: (err: unknown) => {
      const fallback = t('datasources.create.save_error');
      showApiError(message, err, (e) =>
        datasourceCreateErrorMessage(e) ?? extractDetail(e) ?? fallback,
      );
    },
  });

  const submitConnectionForm = useCallback(
    (values: ConnectionFormValues) => {
      persistConnection.mutate(values);
    },
    [persistConnection],
  );

  const submitSettingsForm = useCallback(
    (values: SettingsFormValues) => {
      persistSettings.mutate(values);
    },
    [persistSettings],
  );

  const goBack = () => {
    if (currentStep === 'type') {
      navigate('/datasources');
      return;
    }
    if (currentStep === 'connection') {
      setCurrentStep('type');
      return;
    }
    if (currentStep === 'test') {
      setCurrentStep('connection');
      return;
    }
    if (currentStep === 'settings') {
      setCurrentStep('test');
    }
  };

  const stepBody = useMemo(() => {
    if (currentStep === 'type') {
      if (typesQuery.isLoading) {
        return (
          <div className="muted" style={{ padding: 40, textAlign: 'center' }}>
            {t('datasources.create.types_loading')}
          </div>
        );
      }
      if (typesQuery.isError || !typesQuery.data) {
        return (
          <EmptyState
            title={t('datasources.create.types_error_title')}
            description={t('datasources.create.types_error_body')}
          />
        );
      }
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Alert
            type="info"
            showIcon
            closable={false}
            message={t('datasources.create.driver_policy_callout_title')}
            description={t('datasources.create.driver_policy_callout_body')}
          />
          <DatasourceTypeSelector
            types={typesQuery.data.types}
            selectedKey={selectedType ? optionKey(selectedType) : null}
            onSelect={handleSelectType}
          />
        </div>
      );
    }
    if (currentStep === 'connection' && selectedType) {
      const isSearchEngine = SEARCH_ENGINES.includes(selectedType.code);
      const isDynamoDb = selectedType.code === 'DYNAMODB';
      const isNeo4j = selectedType.code === 'NEO4J';
      const isSnowflake = selectedType.code === 'SNOWFLAKE';
      const isBigQuery = selectedType.code === 'BIGQUERY';
      const isDatabricks = selectedType.code === 'DATABRICKS';
      // Neo4j may connect via host/port OR a full bolt URI override; host/port stop being required
      // once the operator supplies a URI (Aura / clustered routing has no separate host/port).
      const neo4jHasUri = isNeo4j && !!connectionValues?.neo4j_bolt_uri?.trim();
      const hostPortRequired = !neo4jHasUri;
      const authMethod: AuthMethod =
        (connectionValues?.auth_method as AuthMethod | undefined) ?? 'basic';
      const showBasicCreds = !isSearchEngine || authMethod === 'basic';
      return (
        <Form<ConnectionFormValues>
          form={connectionForm}
          layout="vertical"
          requiredMark
          onFinish={submitConnectionForm}
          initialValues={{
            port: selectedType.default_port,
            ssl_mode: selectedType.default_ssl_mode,
            auth_method: 'basic',
          }}
        >
          {dynamicMode && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message={t('datasources.create.dynamic_mode_notice_title')}
              description={t('datasources.create.dynamic_mode_notice_body')}
            />
          )}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: 12,
            }}
          >
            <Form.Item
              label={t('datasources.create.field_name')}
              name="name"
              rules={[{ required: true }, { max: 255 }]}
            >
              <Input autoFocus placeholder={t('datasources.create.field_name_placeholder')} />
            </Form.Item>
            {!dynamicMode && !isDynamoDb && !isBigQuery && (
              <>
                <Form.Item
                  label={
                    isSnowflake
                      ? t('datasources.create.field_account_host')
                      : isDatabricks
                        ? t('datasources.create.field_workspace_host')
                        : t('datasources.create.field_host')
                  }
                  name="host"
                  rules={[{ required: hostPortRequired }, { max: 255 }]}
                >
                  <Input
                    placeholder={
                      isSnowflake
                        ? 'xy12345.eu-central-1.snowflakecomputing.com'
                        : isDatabricks
                          ? 'adb-1234567890.1.azuredatabricks.net'
                          : 'db.internal'
                    }
                  />
                </Form.Item>
                {/* Warehouse hosts are HTTPS endpoints — port is always 443 and never asked. */}
                {!isSnowflake && !isDatabricks && (
                  <Form.Item
                    label={t('datasources.create.field_port')}
                    name="port"
                    rules={[{ required: hostPortRequired, type: 'number', min: 1, max: 65535 }]}
                  >
                    <InputNumber style={{ width: '100%' }} />
                  </Form.Item>
                )}
              </>
            )}
            {!dynamicMode && (
              <Form.Item
                label={
                  isDynamoDb
                    ? t('datasources.create.field_region')
                    : isBigQuery
                      ? t('datasources.create.field_gcp_project')
                      : isDatabricks
                        ? t('datasources.create.field_catalog')
                        : t('datasources.create.field_database')
                }
                name="database_name"
                extra={
                  isDynamoDb
                    ? t('datasources.create.field_region_help')
                    : isBigQuery
                      ? t('datasources.create.field_gcp_project_help')
                      : isDatabricks
                        ? t('datasources.create.field_catalog_help')
                        : undefined
                }
                rules={
                  isSearchEngine || isDatabricks
                    ? [{ max: 255 }]
                    : isBigQuery
                      ? [
                          { required: true },
                          { max: 255 },
                          {
                            pattern: /^[^.\s]+(\.[^.\s]+)?$/,
                            message: t('datasources.create.field_gcp_project_help'),
                          },
                        ]
                      : [{ required: true }, { max: 255 }]
                }
              >
                <Input
                  placeholder={
                    isDynamoDb
                      ? 'us-east-1'
                      : isSearchEngine
                        ? 'logs-*'
                        : isBigQuery
                          ? 'my-project.analytics'
                          : isDatabricks
                            ? 'main'
                            : isSnowflake
                              ? 'ANALYTICS'
                              : 'appdb'
                  }
                />
              </Form.Item>
            )}
            {isSnowflake && (
              <Form.Item
                label={t('datasources.create.field_snowflake_url_override')}
                name="snowflake_url_override"
                extra={t('datasources.create.field_snowflake_url_override_help')}
                rules={[
                  { max: 2048 },
                  {
                    pattern: /^jdbc:snowflake:\/\/.+$/,
                    message: t('datasources.create.field_snowflake_url_override_help'),
                  },
                ]}
              >
                <Input placeholder="jdbc:snowflake://…/?warehouse=WH&role=GOV" />
              </Form.Item>
            )}
            {isBigQuery && (
              <Form.Item
                label={t('datasources.create.field_bigquery_endpoint')}
                name="bigquery_endpoint"
                extra={t('datasources.create.field_bigquery_endpoint_help')}
                rules={[{ max: 2048 }]}
              >
                <Input placeholder="http://localhost:9050" />
              </Form.Item>
            )}
            {isDatabricks && (
              <Form.Item
                label={t('datasources.create.field_databricks_http_path')}
                name="databricks_http_path"
                extra={t('datasources.create.field_databricks_http_path_help')}
                rules={[
                  {
                    required: true,
                    message: t('datasources.create.field_databricks_http_path_help'),
                  },
                  { max: 2048 },
                  {
                    pattern: /^(https?:\/\/[^/\s]+)?\/sql\/[^/\s]+\/warehouses\/[A-Za-z0-9-]+\/?$/,
                    message: t('datasources.create.field_databricks_http_path_help'),
                  },
                ]}
              >
                <Input placeholder="/sql/1.0/warehouses/abc123def456" />
              </Form.Item>
            )}
            {isDynamoDb && (
              <Form.Item
                label={t('datasources.create.field_dynamodb_endpoint')}
                name="dynamodb_endpoint"
                extra={t('datasources.create.field_dynamodb_endpoint_help')}
                rules={[{ max: 2048 }]}
              >
                <Input placeholder="http://localhost:8000" />
              </Form.Item>
            )}
            {isNeo4j && (
              <Form.Item
                label={t('datasources.create.field_neo4j_bolt_uri')}
                name="neo4j_bolt_uri"
                extra={t('datasources.create.field_neo4j_bolt_uri_help')}
                rules={[{ max: 2048 }]}
              >
                <Input placeholder="neo4j+s://xxxx.databases.neo4j.io" />
              </Form.Item>
            )}
            {(selectedType.code === 'CASSANDRA' || selectedType.code === 'SCYLLADB') && (
              <Form.Item
                label={t('datasources.create.field_local_datacenter')}
                name="local_datacenter"
                extra={t('datasources.create.field_local_datacenter_help')}
                rules={[{ required: true }, { max: 255 }]}
              >
                <Input placeholder="datacenter1" />
              </Form.Item>
            )}
            {isSearchEngine && (
              <Form.Item label={t('datasources.create.field_auth_method')} name="auth_method">
                <Radio.Group
                  optionType="button"
                  options={[
                    {
                      value: 'basic',
                      label: t('datasources.create.field_auth_method_basic'),
                    },
                    {
                      value: 'api_key',
                      label: t('datasources.create.field_auth_method_api_key'),
                    },
                  ]}
                />
              </Form.Item>
            )}
            {showBasicCreds && (
              <>
                {/* BigQuery (service-account JSON) and Databricks (PAT) have no username. */}
                {!isBigQuery && !isDatabricks && (
                  <Form.Item
                    label={
                      isDynamoDb
                        ? t('datasources.create.field_access_key_id')
                        : t('datasources.create.field_username')
                    }
                    name="username"
                    rules={[{ required: true }, { max: 255 }]}
                  >
                    <Input placeholder={isDynamoDb ? 'AKIA…' : 'accessflow_svc'} />
                  </Form.Item>
                )}
                <Form.Item
                  label={
                    isDynamoDb
                      ? t('datasources.create.field_secret_access_key')
                      : isBigQuery
                        ? t('datasources.create.field_service_account_json')
                        : isDatabricks
                          ? t('datasources.create.field_access_token')
                          : isSnowflake
                            ? t('datasources.create.field_password_or_key')
                            : t('datasources.create.field_password')
                  }
                  name="password"
                  extra={
                    isSnowflake
                      ? secretRefHelp
                        ? `${t('datasources.create.field_password_or_key_help')} ${secretRefHelp}`
                        : t('datasources.create.field_password_or_key_help')
                      : secretRefHelp
                  }
                  rules={[{ required: true }, { max: 4096 }, secretRefRule]}
                >
                  {/* A service-account JSON / PKCS#8 PEM is multi-line — a password box can't hold it. */}
                  {isBigQuery || isSnowflake ? (
                    <Input.TextArea rows={3} className="mono" />
                  ) : (
                    <Input.Password />
                  )}
                </Form.Item>
              </>
            )}
            {isSearchEngine && authMethod === 'api_key' && (
              <Form.Item
                label={t('datasources.create.field_api_key')}
                name="api_key"
                extra={
                  secretRefHelp
                    ? `${t('datasources.create.field_api_key_help')} ${secretRefHelp}`
                    : t('datasources.create.field_api_key_help')
                }
                rules={[
                  { required: true, message: t('datasources.create.field_api_key_required') },
                  { max: 4096 },
                  secretRefRule,
                ]}
              >
                <Input.Password />
              </Form.Item>
            )}
            <Form.Item
              label={t('datasources.create.field_ssl_mode')}
              name="ssl_mode"
              rules={[{ required: true }]}
            >
              <Select
                options={enumOptions(SSL_MODES, sslModeLabel, t)}
              />
            </Form.Item>
          </div>
          {dynamicMode && (
            <Form.Item
              label={t('datasources.create.field_jdbc_url')}
              name="jdbc_url"
              extra={t('datasources.create.field_jdbc_url_help')}
              rules={[
                { required: true, message: t('datasources.create.field_jdbc_url_help') },
                { max: 2048 },
                {
                  pattern: /^jdbc:[a-zA-Z][a-zA-Z0-9+\-.]*:.+$/,
                  message: t('datasources.create.field_jdbc_url_placeholder'),
                },
              ]}
            >
              <Input.TextArea
                rows={2}
                className="mono"
                placeholder={t('datasources.create.field_jdbc_url_placeholder')}
              />
            </Form.Item>
          )}
          {selectedType.code === 'MYSQL' && connectionValues?.ssl_mode === 'DISABLE' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 8 }}
              message={t('datasources.create.mysql_public_key_retrieval_title')}
              description={t('datasources.create.mysql_public_key_retrieval_body')}
            />
          )}
          {!dynamicMode && (
            <div style={{ marginTop: 8 }}>
              <JdbcUrlPreview
                template={previewedTemplate}
                host={connectionValues?.host}
                port={connectionValues?.port}
                databaseName={connectionValues?.database_name}
              />
            </div>
          )}
        </Form>
      );
    }
    if (currentStep === 'test' && selectedType && createdDatasource) {
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="muted" style={{ fontSize: 13 }}>
            {t('datasources.create.test_step_subtitle')}
          </div>
          <ConnectionTester
            driverStatus={selectedType.driver_status}
            pending={testMutation.isPending}
            result={testResult}
            errorMessage={testError}
            onRunTest={() => testMutation.mutate()}
          />
        </div>
      );
    }
    if (currentStep === 'settings' && createdDatasource) {
      return (
        <Form<SettingsFormValues>
          form={settingsForm}
          layout="vertical"
          requiredMark
          onFinish={submitSettingsForm}
          initialValues={{
            connection_pool_size: createdDatasource.connection_pool_size,
            max_rows_per_query: createdDatasource.max_rows_per_query,
            review_plan_id: createdDatasource.review_plan_id ?? null,
            require_review_reads: createdDatasource.require_review_reads,
            require_review_writes: createdDatasource.require_review_writes,
            ai_analysis_enabled: createdDatasource.ai_analysis_enabled,
            ai_config_id: createdDatasource.ai_config_id ?? null,
            text_to_sql_enabled: createdDatasource.text_to_sql_enabled,
          }}
        >
          <div className="muted" style={{ fontSize: 13, marginBottom: 16 }}>
            {t('datasources.create.settings_step_subtitle')}
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
              gap: 12,
            }}
          >
            <Form.Item
              label={t('datasources.create.label_pool_size')}
              name="connection_pool_size"
              extra={t('datasources.create.label_pool_size_help')}
              rules={[{ required: true, type: 'number', min: 1, max: 200 }]}
            >
              <InputNumber style={{ width: '100%' }} min={1} max={200} />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.label_max_rows')}
              name="max_rows_per_query"
              extra={t('datasources.create.label_max_rows_help')}
              rules={[{ required: true, type: 'number', min: 1, max: 1_000_000 }]}
            >
              <InputNumber style={{ width: '100%' }} min={1} max={1_000_000} />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.label_review_plan')}
              name="review_plan_id"
            >
              <Select
                allowClear
                loading={reviewPlansQuery.isLoading}
                placeholder={
                  reviewPlansQuery.isLoading
                    ? t('datasources.create.review_plan_loading')
                    : t('datasources.create.review_plan_placeholder')
                }
                options={(reviewPlansQuery.data ?? []).map((plan) => ({
                  value: plan.id,
                  label: plan.name,
                }))}
              />
            </Form.Item>
            <div />
            <Form.Item
              label={t('datasources.create.label_require_writes')}
              name="require_review_writes"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.label_require_reads')}
              name="require_review_reads"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_ai_analysis_enabled')}
              name="ai_analysis_enabled"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_text_to_sql_enabled')}
              name="text_to_sql_enabled"
              valuePropName="checked"
              extra={t('datasources.create.field_text_to_sql_help')}
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_ai_config')}
              name="ai_config_id"
              dependencies={['ai_analysis_enabled', 'text_to_sql_enabled']}
              rules={[
                ({ getFieldValue }) => ({
                  required:
                    getFieldValue('ai_analysis_enabled') === true ||
                    getFieldValue('text_to_sql_enabled') === true,
                  message: t('datasources.create.field_ai_config_required'),
                }),
              ]}
              extra={
                <a href="/admin/ai-configs/new" target="_blank" rel="noopener noreferrer">
                  {t('datasources.create.add_ai_config_link')}
                </a>
              }
            >
              <Select
                allowClear
                disabled={
                  !settingsValues?.ai_analysis_enabled && !settingsValues?.text_to_sql_enabled
                }
                placeholder={t('datasources.create.field_ai_config_placeholder')}
                options={(aiConfigsQuery.data ?? []).map((c) => ({
                  value: c.id,
                  label: `${c.name} · ${aiProviderLabel(t, c.provider)}`,
                }))}
              />
            </Form.Item>
          </div>
        </Form>
      );
    }
    return null;
  }, [
    currentStep,
    typesQuery.isLoading,
    typesQuery.isError,
    typesQuery.data,
    selectedType,
    dynamicMode,
    connectionForm,
    settingsForm,
    previewedTemplate,
    connectionValues,
    settingsValues,
    createdDatasource,
    testResult,
    testError,
    testMutation,
    aiConfigsQuery.data,
    reviewPlansQuery.isLoading,
    reviewPlansQuery.data,
    t,
    submitConnectionForm,
    submitSettingsForm,
    handleSelectType,
  ]);

  const connectionPrimaryLabel = createdDatasource
    ? t('datasources.create.save_and_continue')
    : t('datasources.create.save_and_test');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-datasources"
        title={t('datasources.create.title')}
        subtitle={t('datasources.create.subtitle')}
        actions={
          currentStep === 'type' || currentStep === 'connection' ? (
            <Button icon={<ArrowLeftOutlined />} onClick={goBack}>
              {t('datasources.create.back')}
            </Button>
          ) : null
        }
      />
      <div
        style={{
          padding: '20px 28px',
          background: 'var(--bg-elev)',
          borderBottom: '1px solid var(--border)',
        }}
      >
        <DatasourceWizardSteps current={currentStep} />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>{stepBody}</div>
      <div
        style={{
          padding: '12px 28px',
          borderTop: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          justifyContent: 'flex-end',
          gap: 8,
        }}
      >
        {currentStep === 'connection' && (
          <Button
            type="primary"
            onClick={() => connectionForm.submit()}
            loading={persistConnection.isPending}
          >
            {connectionPrimaryLabel}
          </Button>
        )}
        {currentStep === 'test' && (
          <>
            <Button onClick={() => setCurrentStep('settings')}>
              {t('datasources.create.skip_test')}
            </Button>
            <Button
              type="primary"
              disabled={!testResult?.ok}
              onClick={() => setCurrentStep('settings')}
            >
              {t('datasources.create.next')}
            </Button>
          </>
        )}
        {currentStep === 'settings' && (
          <Button
            type="primary"
            onClick={() => settingsForm.submit()}
            loading={persistSettings.isPending}
          >
            {t('datasources.create.save_and_finish')}
          </Button>
        )}
      </div>
    </div>
  );
}

interface AxiosLikeError {
  response?: { data?: { detail?: string; title?: string } };
  message?: string;
}

function extractDetail(err: unknown): string | null {
  const axiosErr = err as AxiosLikeError;
  return (
    axiosErr?.response?.data?.detail ??
    axiosErr?.response?.data?.title ??
    axiosErr?.message ??
    null
  );
}
