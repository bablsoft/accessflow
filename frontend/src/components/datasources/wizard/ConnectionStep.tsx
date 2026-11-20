import { Alert, Form, Input, InputNumber, Radio, Select } from 'antd';
import type { FormInstance } from 'antd';
import { useTranslation } from 'react-i18next';
import { Section } from '@/components/layout';
import { enumOptions, sslModeLabel } from '@/utils/enumLabels';
import { secretReferenceHelp, secretReferenceRule } from '@/utils/secretReference';
import { useSecretProviders } from '@/hooks/useSecretProviders';
import type { DatasourceTypeOption, SslMode } from '@/types/api';
import { JdbcUrlPreview } from '@/components/datasources/JdbcUrlPreview';
import type { AuthMethod, ConnectionFormValues } from './connectionPayload';
import { engineFlags } from './engineFlags';

const SSL_MODES: SslMode[] = ['DISABLE', 'REQUIRE', 'VERIFY_CA', 'VERIFY_FULL'];

interface ConnectionStepProps {
  form: FormInstance<ConnectionFormValues>;
  formId: string;
  selectedType: DatasourceTypeOption;
  onFinish: (values: ConnectionFormValues) => void;
  onValuesChange: () => void;
}

/** The connection form: where the database is and how AccessFlow signs in to it. */
export function ConnectionStep({
  form,
  formId,
  selectedType,
  onFinish,
  onValuesChange,
}: ConnectionStepProps) {
  const { t } = useTranslation();
  const secretProviders = useSecretProviders();
  const secretRefHelp = secretReferenceHelp(secretProviders, t);
  const secretRefRule = secretReferenceRule(secretProviders, t);
  const connectionValues = Form.useWatch([], form);
  const {
    isDynamic: dynamicMode,
    isSearchEngine,
    isDynamoDb,
    isNeo4j,
    isSnowflake,
    isBigQuery,
    isDatabricks,
  } = engineFlags(selectedType);
  // Neo4j may connect via host/port OR a full bolt URI override; host/port stop being required
  // once the operator supplies a URI (Aura / clustered routing has no separate host/port).
  const neo4jHasUri = isNeo4j && !!connectionValues?.neo4j_bolt_uri?.trim();
  const hostPortRequired = !neo4jHasUri;
  const authMethod: AuthMethod = (connectionValues?.auth_method as AuthMethod | undefined) ?? 'basic';
  const showBasicCreds = !isSearchEngine || authMethod === 'basic';
  const genericHost = !isSnowflake && !isDatabricks;

  return (
    <Form<ConnectionFormValues>
      id={formId}
      form={form}
      layout="vertical"
      requiredMark
      onFinish={onFinish}
      onValuesChange={onValuesChange}
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
          className="u-mb-4"
          title={t('datasources.create.dynamic_mode_notice_title')}
          description={t('datasources.create.dynamic_mode_notice_body')}
        />
      )}
      <Section
        title={t('datasources.create.section_endpoint')}
        description={t('datasources.create.section_endpoint_help')}
      >
        <div className="af-wizard-fields">
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
                extra={genericHost ? t('datasources.create.field_host_help') : undefined}
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
                  <InputNumber className="u-w-full" />
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
          <Form.Item
            label={t('datasources.create.field_ssl_mode')}
            name="ssl_mode"
            rules={[{ required: true }]}
          >
            <Select
              options={enumOptions(SSL_MODES, sslModeLabel, t)}
            />
          </Form.Item>
          {dynamicMode && (
            <div className="af-wizard-fields-wide">
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
            </div>
          )}
        </div>
        {selectedType.code === 'MYSQL' && connectionValues?.ssl_mode === 'DISABLE' && (
          <Alert
            type="warning"
            showIcon
            className="u-mb-4"
            title={t('datasources.create.mysql_public_key_retrieval_title')}
            description={t('datasources.create.mysql_public_key_retrieval_body')}
          />
        )}
        {!dynamicMode && (
          <JdbcUrlPreview
            template={selectedType.jdbc_url_template ?? ''}
            host={connectionValues?.host}
            port={connectionValues?.port}
            databaseName={connectionValues?.database_name}
          />
        )}
      </Section>
      <Section
        title={t('datasources.create.section_credentials')}
        description={t('datasources.create.section_credentials_help')}
      >
        <div className="af-wizard-fields">
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
              {isSnowflake && (
                <Form.Item
                  label={t('datasources.create.field_private_key_passphrase')}
                  name="private_key_passphrase"
                  extra={
                    secretRefHelp
                      ? `${t('datasources.create.field_private_key_passphrase_help')} ${secretRefHelp}`
                      : t('datasources.create.field_private_key_passphrase_help')
                  }
                  rules={[{ max: 1024 }, secretRefRule]}
                >
                  <Input.Password />
                </Form.Item>
              )}
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
        </div>
      </Section>
    </Form>
  );
}
