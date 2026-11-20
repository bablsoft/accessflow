import { useCallback, useState } from 'react';
import { flushSync } from 'react-dom';
import { Alert, App, Button, Form } from 'antd';
import { ArrowLeftOutlined, EditOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import {
  UnsavedChangesGuard,
  WizardFooter,
  WizardLayout,
  WizardStep,
  WizardSteps,
  useConfirm,
  type WizardStepDef,
} from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import {
  createDatasource,
  datasourceKeys,
  getDatasourceTypes,
  testConnection,
  updateDatasource,
} from '@/api/datasources';
import { setupProgressKeys } from '@/api/admin';
import { apiErrorTraceId, datasourceCreateErrorMessage } from '@/utils/apiErrors';
import { supportsBytesCap } from '@/utils/bytesCap';
import { toEnvironmentCreate } from '@/utils/datasourceEnvironment';
import { useWizardStep } from '@/hooks/useWizardStep';
import type {
  ConnectionTestResult,
  Datasource,
  DatasourceTypeOption,
  UpdateDatasourceInput,
} from '@/types/api';
import { DatasourceTypeSelector, optionKey } from '@/components/datasources/DatasourceTypeSelector';
import { ConnectionTester } from '@/components/datasources/ConnectionTester';
import { ConnectionStep } from '@/components/datasources/wizard/ConnectionStep';
import {
  SettingsStep,
  type SettingsFormValues,
} from '@/components/datasources/wizard/SettingsStep';
import {
  toCreateInput,
  toUpdateInput,
  type ConnectionFormValues,
} from '@/components/datasources/wizard/connectionPayload';

type StepKey = 'type' | 'connection' | 'test' | 'settings';
const STEP_KEYS: readonly StepKey[] = ['type', 'connection', 'test', 'settings'];
const CONNECTION_FORM_ID = 'datasource-wizard-connection';
const SETTINGS_FORM_ID = 'datasource-wizard-settings';

interface SaveError {
  text: string;
  traceId: string | null;
}

export default function DatasourceCreateWizardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const confirm = useConfirm();
  const [selectedType, setSelectedType] = useState<DatasourceTypeOption | null>(null);
  const [connectionForm] = Form.useForm<ConnectionFormValues>();
  const [settingsForm] = Form.useForm<SettingsFormValues>();
  const [createdDatasource, setCreatedDatasource] = useState<Datasource | null>(null);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);
  const [testError, setTestError] = useState<string | null>(null);
  const [connectionDirty, setConnectionDirty] = useState(false);
  const [settingsDirty, setSettingsDirty] = useState(false);
  const [saveError, setSaveError] = useState<SaveError | null>(null);
  const dirty = connectionDirty || settingsDirty;

  // The database type is fixed once the datasource exists, so that step closes behind it.
  const canShow = useCallback(
    (key: StepKey) => {
      if (key === 'type') return !createdDatasource;
      if (key === 'connection') return !!selectedType;
      return !!createdDatasource;
    },
    [createdDatasource, selectedType],
  );
  const [currentStep, goToStep] = useWizardStep(
    STEP_KEYS,
    createdDatasource ? 'connection' : 'type',
    canShow,
  );
  const openStep = useCallback(
    (key: StepKey) => {
      setSaveError(null);
      goToStep(key);
    },
    [goToStep],
  );

  const typesQuery = useQuery({
    queryKey: datasourceKeys.types(),
    queryFn: getDatasourceTypes,
  });

  const handleSelectType = useCallback(
    (option: DatasourceTypeOption) => {
      setSelectedType(option);
      connectionForm.setFieldsValue({
        port: option.default_port,
        ssl_mode: option.default_ssl_mode,
      });
      openStep('connection');
    },
    [connectionForm, openStep],
  );

  const reportSaveError = (err: unknown) => {
    const text =
      datasourceCreateErrorMessage(err) ?? extractDetail(err) ?? t('datasources.create.save_error');
    setSaveError({ text, traceId: apiErrorTraceId(err) ?? null });
  };

  const persistConnection = useMutation({
    mutationFn: async (values: ConnectionFormValues) => {
      if (!selectedType) {
        throw new Error('No datasource type selected');
      }
      return createdDatasource
        ? updateDatasource(createdDatasource.id, toUpdateInput(selectedType, values))
        : createDatasource(toCreateInput(selectedType, values));
    },
    onMutate: () => setSaveError(null),
    onSuccess: (saved) => {
      setCreatedDatasource(saved);
      setConnectionDirty(false);
      queryClient.setQueryData(datasourceKeys.detail(saved.id), saved);
      queryClient.invalidateQueries({ queryKey: datasourceKeys.lists() });
      queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      setTestResult(null);
      setTestError(null);
      goToStep('test');
    },
    onError: (err: unknown) => {
      // A taken name belongs on the name field, where the fix is made.
      if (isNameConflict(err)) {
        connectionForm.setFields([
          { name: 'name', errors: [datasourceCreateErrorMessage(err) ?? ''] },
        ]);
        connectionForm.scrollToField('name');
        return;
      }
      reportSaveError(err);
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
      // A freshly created row has no environment yet, so "not set" needs no clear flag (#865).
      const environment = toEnvironmentCreate(values.environment);
      if (environment) {
        input.environment = environment;
      }
      if (!values.ai_analysis_enabled && !values.text_to_sql_enabled) {
        input.clear_ai_config = true;
      }
      // Bytes-scanned cap (#941): warehouse engines only, and never sent elsewhere.
      if (supportsBytesCap(createdDatasource.db_type)) {
        if (typeof values.max_bytes_scanned_per_query === 'number') {
          input.max_bytes_scanned_per_query = values.max_bytes_scanned_per_query;
        }
        if (values.bytes_cap_missing_estimate) {
          input.bytes_cap_missing_estimate = values.bytes_cap_missing_estimate;
        }
      }
      return updateDatasource(createdDatasource.id, input);
    },
    onMutate: () => setSaveError(null),
    onSuccess: (saved) => {
      queryClient.setQueryData(datasourceKeys.detail(saved.id), saved);
      queryClient.invalidateQueries({ queryKey: datasourceKeys.lists() });
      message.success(t('datasources.create.save_success'));
      leave(`/datasources/${saved.id}/settings`);
    },
    onError: reportSaveError,
  });

  // The unsaved-changes guard reads `dirty` during navigation, so it has to be cleared first.
  const leave = (to: string) => {
    flushSync(() => {
      setConnectionDirty(false);
      setSettingsDirty(false);
    });
    navigate(to);
  };

  const cancel = () => {
    if (createdDatasource) {
      confirm({
        title: t('datasources.create.cancel_saved_title'),
        content: t('datasources.create.cancel_saved_body', { name: createdDatasource.name }),
        okText: t('datasources.create.cancel_leave'),
        onOk: () => leave('/datasources'),
      });
      return;
    }
    if (dirty) {
      confirm({
        title: t('common.unsaved_title'),
        content: t('common.unsaved_body'),
        okText: t('common.unsaved_discard'),
        onOk: () => leave('/datasources'),
      });
      return;
    }
    navigate('/datasources');
  };

  const steps: WizardStepDef<StepKey>[] = [
    { key: 'type', title: t('datasources.create.step_type') },
    { key: 'connection', title: t('datasources.create.step_connection') },
    { key: 'test', title: t('datasources.create.step_test'), optional: true },
    { key: 'settings', title: t('datasources.create.step_settings') },
  ];

  const previousStep: StepKey | null =
    currentStep === 'settings'
      ? 'test'
      : currentStep === 'test'
        ? 'connection'
        : currentStep === 'connection' && !createdDatasource
          ? 'type'
          : null;

  const testFailed = !testMutation.isPending && (testError != null || testResult?.ok === false);

  let status: string | null = null;
  if (dirty) status = t('common.unsaved_hint');
  else if (createdDatasource) {
    status = t('datasources.create.saved_status', { name: createdDatasource.name });
  }

  const errorAlert = saveError && (
    <Alert
      type="error"
      showIcon
      closable={{ onClose: () => setSaveError(null) }}
      className="u-mb-4"
      title={saveError.text}
      description={saveError.traceId ? <TraceIdFooter traceId={saveError.traceId} /> : undefined}
    />
  );

  return (
    <WizardLayout
      stepKey={currentStep}
      header={
        <PageHeader
          docsAnchor="cfg-datasources"
          title={t('datasources.create.title')}
          subtitle={t('datasources.create.subtitle')}
        />
      }
      steps={
        <WizardSteps
          ariaLabel={t('datasources.create.steps_label')}
          steps={steps}
          current={currentStep}
          canOpen={canShow}
          onOpen={openStep}
        />
      }
      footer={
        <WizardFooter
          start={
            <>
              {previousStep && (
                <Button icon={<ArrowLeftOutlined />} onClick={() => openStep(previousStep)}>
                  {t('datasources.create.back')}
                </Button>
              )}
              <Button type="text" onClick={cancel}>
                {t('common.cancel')}
              </Button>
            </>
          }
          status={status}
          end={
            <>
              {currentStep === 'connection' &&
                (createdDatasource && !connectionDirty ? (
                  <Button type="primary" onClick={() => openStep('test')}>
                    {t('datasources.create.next')}
                  </Button>
                ) : (
                  <Button
                    type="primary"
                    htmlType="submit"
                    form={CONNECTION_FORM_ID}
                    loading={persistConnection.isPending}
                  >
                    {createdDatasource
                      ? t('datasources.create.save_changes_and_retest')
                      : t('datasources.create.save_and_test')}
                  </Button>
                ))}
              {currentStep === 'test' && (
                <>
                  <Button onClick={() => openStep('settings')}>
                    {t('datasources.create.skip_test')}
                  </Button>
                  <Button
                    type="primary"
                    disabled={!testResult?.ok}
                    onClick={() => openStep('settings')}
                  >
                    {t('datasources.create.next')}
                  </Button>
                </>
              )}
              {currentStep === 'settings' && (
                <Button
                  type="primary"
                  htmlType="submit"
                  form={SETTINGS_FORM_ID}
                  loading={persistSettings.isPending}
                >
                  {t('datasources.create.save_and_finish')}
                </Button>
              )}
            </>
          }
        />
      }
    >
      <UnsavedChangesGuard when={dirty} />
      {currentStep === 'type' && (
        <WizardStep
          title={t('datasources.create.step_type')}
          description={t('datasources.create.type_step_subtitle')}
        >
          {typesQuery.isLoading && (
            <div className="muted u-p-10 u-text-center">{t('datasources.create.types_loading')}</div>
          )}
          {!typesQuery.isLoading && (typesQuery.isError || !typesQuery.data) && (
            <EmptyState
              title={t('datasources.create.types_error_title')}
              description={t('datasources.create.types_error_body')}
            />
          )}
          {typesQuery.data && (
            <div className="u-flex u-col u-gap-4">
              <Alert
                type="info"
                showIcon
                closable={false}
                title={t('datasources.create.driver_policy_callout_title')}
                description={t('datasources.create.driver_policy_callout_body')}
              />
              <DatasourceTypeSelector
                types={typesQuery.data.types}
                selectedKey={selectedType ? optionKey(selectedType) : null}
                onSelect={handleSelectType}
              />
            </div>
          )}
        </WizardStep>
      )}
      {currentStep === 'connection' && selectedType && (
        <WizardStep
          title={t('datasources.create.step_connection')}
          description={t('datasources.create.connection_step_subtitle', {
            type: selectedType.display_name,
          })}
        >
          {errorAlert}
          <ConnectionStep
            form={connectionForm}
            formId={CONNECTION_FORM_ID}
            selectedType={selectedType}
            onFinish={(values) => persistConnection.mutate(values)}
            onValuesChange={() => setConnectionDirty(true)}
          />
        </WizardStep>
      )}
      {currentStep === 'test' && selectedType && createdDatasource && (
        <WizardStep
          title={t('datasources.create.step_test')}
          description={t('datasources.create.test_step_subtitle')}
        >
          <ConnectionTester
            driverStatus={selectedType.driver_status}
            pending={testMutation.isPending}
            result={testResult}
            errorMessage={testError}
            onRunTest={() => testMutation.mutate()}
          />
          {testFailed && (
            <Button
              className="u-mt-3"
              icon={<EditOutlined />}
              onClick={() => openStep('connection')}
            >
              {t('datasources.create.edit_connection')}
            </Button>
          )}
        </WizardStep>
      )}
      {currentStep === 'settings' && createdDatasource && (
        <WizardStep
          title={t('datasources.create.step_settings')}
          description={t('datasources.create.settings_step_subtitle')}
        >
          {errorAlert}
          <SettingsStep
            form={settingsForm}
            formId={SETTINGS_FORM_ID}
            createdDatasource={createdDatasource}
            onFinish={(values) => persistSettings.mutate(values)}
            onValuesChange={() => setSettingsDirty(true)}
          />
        </WizardStep>
      )}
    </WizardLayout>
  );
}

function isNameConflict(err: unknown): boolean {
  return (
    axios.isAxiosError<{ error?: string }>(err) &&
    err.response?.data?.error === 'DATASOURCE_NAME_ALREADY_EXISTS'
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
