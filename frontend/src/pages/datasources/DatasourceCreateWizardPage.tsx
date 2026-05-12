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
} from '@/api/datasources';
import { aiConfigKeys, listAiConfigs, setupProgressKeys } from '@/api/admin';
import { datasourceCreateErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  ConnectionTestResult,
  CreateDatasourceInput,
  DatasourceTypeOption,
  SslMode,
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
  const [form] = Form.useForm<ConnectionFormValues>();
  const [createdDatasourceId, setCreatedDatasourceId] = useState<string | null>(null);
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

  const previewValues = Form.useWatch([], form);
  const previewedTemplate = selectedType?.jdbc_url_template ?? '';

  const handleSelectType = useCallback(
    (option: DatasourceTypeOption) => {
      setSelectedType(option);
      form.setFieldsValue({
        port: option.default_port,
        ssl_mode: option.default_ssl_mode,
      });
      setCurrentStep('connection');
    },
    [form],
  );

  const createMutation = useMutation({
    mutationFn: async (values: ConnectionFormValues) => {
      if (!selectedType) {
        throw new Error('No datasource type selected');
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
        ai_analysis_enabled: values.ai_analysis_enabled,
        ai_config_id: values.ai_analysis_enabled ? values.ai_config_id : null,
      };
      return createDatasource(input);
    },
    onSuccess: (created) => {
      setCreatedDatasourceId(created.id);
      queryClient.invalidateQueries({ queryKey: datasourceKeys.lists() });
      queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
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
      if (!createdDatasourceId) {
        throw new Error('No persisted datasource id');
      }
      return testConnection(createdDatasourceId);
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

  const submitConnectionForm = (values: ConnectionFormValues) => {
    createMutation.mutate(values);
  };

  const finishWizard = () => {
    if (!createdDatasourceId) return;
    message.success(t('datasources.create.save_success'));
    navigate(`/datasources/${createdDatasourceId}/settings`);
  };

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
      // After save we don't allow going back to edit the same persisted record;
      // the user can either finish or open the settings page from the success screen.
      setCurrentStep('connection');
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
          form={form}
          layout="vertical"
          requiredMark
          onFinish={submitConnectionForm}
          initialValues={{
            port: selectedType.default_port,
            ssl_mode: selectedType.default_ssl_mode,
            ai_analysis_enabled: false,
            ai_config_id: null,
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
          {selectedType.code === 'MYSQL' && previewValues?.ssl_mode === 'DISABLE' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 8 }}
              message={t('datasources.create.mysql_public_key_retrieval_title')}
              description={t('datasources.create.mysql_public_key_retrieval_body')}
            />
          )}
          <div
            style={{
              marginTop: 16,
              paddingTop: 16,
              borderTop: '1px solid var(--border)',
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
              gap: 12,
            }}
          >
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
                disabled={!previewValues?.ai_analysis_enabled}
                placeholder={t('datasources.create.field_ai_config_placeholder')}
                options={(aiConfigsQuery.data ?? []).map((c) => ({
                  value: c.id,
                  label: `${c.name} · ${c.provider}`,
                }))}
              />
            </Form.Item>
          </div>
          <div style={{ marginTop: 8 }}>
            <JdbcUrlPreview
              template={previewedTemplate}
              host={previewValues?.host}
              port={previewValues?.port}
              databaseName={previewValues?.database_name}
            />
          </div>
        </Form>
      );
    }
    if (currentStep === 'test' && selectedType && createdDatasourceId) {
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
    return null;
  }, [
    currentStep,
    typesQuery.isLoading,
    typesQuery.isError,
    typesQuery.data,
    selectedType,
    form,
    previewedTemplate,
    previewValues,
    createdDatasourceId,
    testResult,
    testError,
    testMutation,
    aiConfigsQuery.data,
    t,
    submitConnectionForm,
    handleSelectType,
  ]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('datasources.create.title')}
        subtitle={t('datasources.create.subtitle')}
        actions={
          <Button icon={<ArrowLeftOutlined />} onClick={goBack}>
            {t('datasources.create.back')}
          </Button>
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
            onClick={() => form.submit()}
            loading={createMutation.isPending}
          >
            {t('datasources.create.save_and_test')}
          </Button>
        )}
        {currentStep === 'test' && (
          <Button type="primary" onClick={finishWizard}>
            {t('datasources.create.finish')}
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
