import { useCallback, useMemo, useState } from 'react';
import { Alert, App, Button, Form, Input, InputNumber, Select, Switch } from 'antd';
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
import { showApiError } from '@/utils/showApiError';
import type {
  ConnectionTestResult,
  CreateDatasourceInput,
  Datasource,
  DatasourceTypeOption,
  SslMode,
  UpdateDatasourceInput,
} from '@/types/api';
import { DatasourceTypeSelector } from '@/components/datasources/DatasourceTypeSelector';
import {
  DatasourceWizardSteps,
  type WizardStepKey,
} from '@/components/datasources/DatasourceWizardSteps';
import { JdbcUrlPreview } from '@/components/datasources/JdbcUrlPreview';
import { ConnectionTester } from '@/components/datasources/ConnectionTester';

interface ConnectionFormValues {
  name: string;
  host: string;
  port: number;
  database_name: string;
  username: string;
  password: string;
  ssl_mode: SslMode;
}

interface SettingsFormValues {
  connection_pool_size: number;
  max_rows_per_query: number;
  review_plan_id: string | null;
  require_review_reads: boolean;
  require_review_writes: boolean;
  ai_analysis_enabled: boolean;
  ai_config_id: string | null;
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

  const persistConnection = useMutation({
    mutationFn: async (values: ConnectionFormValues) => {
      if (!selectedType) {
        throw new Error('No datasource type selected');
      }
      if (createdDatasource) {
        const input: UpdateDatasourceInput = {
          name: values.name,
          host: values.host,
          port: values.port,
          database_name: values.database_name,
          username: values.username,
          password: values.password,
          ssl_mode: values.ssl_mode,
        };
        return updateDatasource(createdDatasource.id, input);
      }
      const input: CreateDatasourceInput = {
        name: values.name,
        db_type: selectedType.code,
        host: values.host,
        port: values.port,
        database_name: values.database_name,
        username: values.username,
        password: values.password,
        ssl_mode: values.ssl_mode,
        ai_analysis_enabled: false,
        ai_config_id: null,
      };
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
        ai_config_id: values.ai_analysis_enabled ? values.ai_config_id : null,
      };
      if (!values.ai_analysis_enabled) {
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
            selectedCode={selectedType?.code ?? null}
            onSelect={handleSelectType}
          />
        </div>
      );
    }
    if (currentStep === 'connection' && selectedType) {
      return (
        <Form<ConnectionFormValues>
          form={connectionForm}
          layout="vertical"
          requiredMark
          onFinish={submitConnectionForm}
          initialValues={{
            port: selectedType.default_port,
            ssl_mode: selectedType.default_ssl_mode,
          }}
        >
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
            <Form.Item
              label={t('datasources.create.field_host')}
              name="host"
              rules={[{ required: true }, { max: 255 }]}
            >
              <Input placeholder="db.internal" />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_port')}
              name="port"
              rules={[{ required: true, type: 'number', min: 1, max: 65535 }]}
            >
              <InputNumber style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_database')}
              name="database_name"
              rules={[{ required: true }, { max: 255 }]}
            >
              <Input placeholder="appdb" />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_username')}
              name="username"
              rules={[{ required: true }, { max: 255 }]}
            >
              <Input placeholder="accessflow_svc" />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_password')}
              name="password"
              rules={[{ required: true }, { max: 4096 }]}
            >
              <Input.Password />
            </Form.Item>
            <Form.Item
              label={t('datasources.create.field_ssl_mode')}
              name="ssl_mode"
              rules={[{ required: true }]}
            >
              <Select
                options={SSL_MODES.map((m) => ({ value: m, label: m }))}
              />
            </Form.Item>
          </div>
          {selectedType.code === 'MYSQL' && connectionValues?.ssl_mode === 'DISABLE' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 8 }}
              message={t('datasources.create.mysql_public_key_retrieval_title')}
              description={t('datasources.create.mysql_public_key_retrieval_body')}
            />
          )}
          <div style={{ marginTop: 8 }}>
            <JdbcUrlPreview
              template={previewedTemplate}
              host={connectionValues?.host}
              port={connectionValues?.port}
              databaseName={connectionValues?.database_name}
            />
          </div>
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
              label={t('datasources.create.field_ai_config')}
              name="ai_config_id"
              dependencies={['ai_analysis_enabled']}
              rules={[
                ({ getFieldValue }) => ({
                  required: getFieldValue('ai_analysis_enabled') === true,
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
                disabled={!settingsValues?.ai_analysis_enabled}
                placeholder={t('datasources.create.field_ai_config_placeholder')}
                options={(aiConfigsQuery.data ?? []).map((c) => ({
                  value: c.id,
                  label: `${c.name} · ${c.provider}`,
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
