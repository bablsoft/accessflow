import { useState } from 'react';
import { App, Button, Form, Input, InputNumber, Result } from 'antd';
import { ArrowLeftOutlined, CheckOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  aiConfigKeys,
  createAiConfig,
  getDefaultAiPrompt,
  setupProgressKeys,
  testAiConfig,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { AiProvider, CreateAiConfigInput, RagStoreType } from '@/types/api';
import {
  AiConfigWizardSteps,
  type AiConfigWizardStepKey,
} from './AiConfigWizardSteps';
import { RagFormSection } from './RagFormSection';

interface ProviderTile {
  id: AiProvider;
  label: string;
  desc: string;
  defaultModel: string;
  defaultEndpoint?: string;
  needsApiKey: boolean;
  needsEndpoint?: boolean;
}

const PROVIDERS: ProviderTile[] = [
  {
    id: 'OPENAI',
    label: 'OpenAI',
    desc: 'GPT-4o, GPT-4o-mini',
    defaultModel: 'gpt-4o',
    needsApiKey: true,
  },
  {
    id: 'ANTHROPIC',
    label: 'Anthropic',
    desc: 'Claude Sonnet, Haiku',
    defaultModel: 'claude-sonnet-4-20250514',
    needsApiKey: true,
  },
  {
    id: 'OLLAMA',
    label: 'Ollama',
    desc: 'Self-hosted local models',
    defaultModel: 'llama3.1:70b',
    defaultEndpoint: 'http://localhost:11434/api',
    needsApiKey: false,
    needsEndpoint: true,
  },
  {
    id: 'OPENAI_COMPATIBLE',
    label: 'Custom (OpenAI-compatible)',
    desc: 'vLLM, LM Studio, Together, Groq, …',
    defaultModel: '',
    defaultEndpoint: 'https://api.example.com/v1',
    needsApiKey: false,
    needsEndpoint: true,
  },
  {
    id: 'HUGGING_FACE',
    label: 'Hugging Face',
    desc: 'Inference Providers router or local TGI',
    defaultModel: 'meta-llama/Llama-3.3-70B-Instruct',
    defaultEndpoint: 'https://router.huggingface.co/v1',
    needsApiKey: false,
    needsEndpoint: true,
  },
];

interface FormValues {
  name: string;
  model: string;
  endpoint: string;
  api_key: string;
  timeout_ms: number;
  max_prompt_tokens: number;
  max_completion_tokens: number;
  system_prompt_template: string;
  langfuse_prompt_name: string;
  langfuse_prompt_label: string;
  rag_enabled: boolean;
  rag_store_type: RagStoreType | null;
  rag_top_k: number;
  rag_similarity_threshold: number;
  rag_endpoint: string;
  rag_collection: string;
  rag_api_key: string;
  embedding_provider: AiProvider | null;
  embedding_model: string;
  embedding_endpoint: string;
  embedding_api_key: string;
  fallback_priority: number | null;
}

export default function AiConfigCreateWizardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [currentStep, setCurrentStep] = useState<AiConfigWizardStepKey>('provider');
  const [selectedProvider, setSelectedProvider] = useState<ProviderTile | null>(null);
  const [createdId, setCreatedId] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ status: 'OK' | 'ERROR'; detail: string } | null>(
    null,
  );
  const [form] = Form.useForm<FormValues>();

  const createMutation = useMutation({
    mutationFn: async (values: FormValues) => {
      if (!selectedProvider) throw new Error('No provider selected');
      const sendsEndpoint = Boolean(selectedProvider.needsEndpoint);
      const input: CreateAiConfigInput = {
        name: values.name.trim(),
        provider: selectedProvider.id,
        model: values.model.trim(),
        endpoint: sendsEndpoint ? (values.endpoint?.trim() || null) : null,
        api_key: values.api_key || null,
        timeout_ms: values.timeout_ms,
        max_prompt_tokens: values.max_prompt_tokens,
        max_completion_tokens: values.max_completion_tokens,
        system_prompt_template: values.system_prompt_template?.trim() || null,
        langfuse_prompt_name: values.langfuse_prompt_name?.trim() || null,
        langfuse_prompt_label: values.langfuse_prompt_label?.trim() || null,
        rag_enabled: values.rag_enabled,
        rag_store_type: values.rag_enabled ? values.rag_store_type : null,
        rag_top_k: values.rag_top_k,
        rag_similarity_threshold: values.rag_similarity_threshold,
        rag_endpoint:
          values.rag_enabled && values.rag_store_type === 'QDRANT'
            ? values.rag_endpoint?.trim() || null
            : null,
        rag_collection:
          values.rag_enabled && values.rag_store_type === 'QDRANT'
            ? values.rag_collection?.trim() || null
            : null,
        rag_api_key: values.rag_api_key?.trim() || null,
        embedding_provider: values.rag_enabled ? values.embedding_provider : null,
        embedding_model: values.rag_enabled ? values.embedding_model?.trim() || null : null,
        embedding_endpoint: values.embedding_endpoint?.trim() || null,
        embedding_api_key: values.embedding_api_key?.trim() || null,
        fallback_priority: values.fallback_priority ?? null,
      };
      return createAiConfig(input);
    },
    onSuccess: (created) => {
      setCreatedId(created.id);
      void queryClient.invalidateQueries({ queryKey: aiConfigKeys.all });
      void queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      setCurrentStep('test');
    },
    onError: (err: unknown) => showApiError(message, err, adminErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: async () => {
      if (!createdId) throw new Error('No persisted ai_config id');
      return testAiConfig(createdId);
    },
    onSuccess: (result) => setTestResult(result),
    onError: (err: unknown) => showApiError(message, err, adminErrorMessage),
  });

  const defaultPromptQuery = useQuery({
    queryKey: aiConfigKeys.promptDefault(),
    queryFn: getDefaultAiPrompt,
    enabled: false,
  });

  const loadDefaultPrompt = async () => {
    const result = await defaultPromptQuery.refetch();
    if (result.data) {
      form.setFieldValue('system_prompt_template', result.data.template);
    } else if (result.error) {
      showApiError(message, result.error, adminErrorMessage);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-ai"
        title={t('admin.ai_configs.wizard.title')}
        subtitle={t('admin.ai_configs.wizard.subtitle')}
        actions={
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/admin/ai-configs')}>
            {t('common.back')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28, maxWidth: 760 }}>
        <AiConfigWizardSteps current={currentStep} />
        <div style={{ marginTop: 24 }}>
          {currentStep === 'provider' && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12 }}>
              {PROVIDERS.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => {
                    setSelectedProvider(p);
                    form.setFieldsValue({
                      model: p.defaultModel,
                      endpoint: p.defaultEndpoint ?? '',
                      timeout_ms: 30_000,
                      max_prompt_tokens: 8_000,
                      max_completion_tokens: 2_000,
                      rag_enabled: false,
                      rag_top_k: 4,
                      rag_similarity_threshold: 0.5,
                    });
                    setCurrentStep('connection');
                  }}
                  style={{
                    padding: 16,
                    textAlign: 'left',
                    background: 'var(--bg-elev)',
                    border: '1px solid var(--border)',
                    borderRadius: 8,
                    cursor: 'pointer',
                    color: 'var(--fg)',
                  }}
                >
                  <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 4 }}>{p.label}</div>
                  <div className="muted" style={{ fontSize: 12 }}>{p.desc}</div>
                </button>
              ))}
            </div>
          )}
          {currentStep === 'connection' && selectedProvider && (
            <Form<FormValues>
              form={form}
              layout="vertical"
              onFinish={(values) => createMutation.mutate(values)}
            >
              <Form.Item
                name="name"
                label={t('admin.ai_configs.field_name')}
                rules={[{ required: true, message: t('admin.ai_configs.name_required') }, { max: 255 }]}
              >
                <Input maxLength={255} autoFocus />
              </Form.Item>
              <Form.Item
                name="model"
                label={t('admin.ai_configs.field_model')}
                rules={[{ required: true }, { max: 100 }]}
              >
                <Input className="mono" maxLength={100} />
              </Form.Item>
              {selectedProvider.needsEndpoint && (
                <Form.Item
                  name="endpoint"
                  label={t('admin.ai_configs.field_endpoint')}
                  rules={[
                    selectedProvider.id === 'OPENAI_COMPATIBLE'
                      ? {
                          required: true,
                          message: t('admin.ai_configs.endpoint_required'),
                        }
                      : {},
                    { max: 500 },
                  ]}
                >
                  <Input className="mono" maxLength={500} />
                </Form.Item>
              )}
              <Form.Item
                name="api_key"
                label={t('admin.ai_configs.field_api_key')}
                rules={[
                  selectedProvider.needsApiKey
                    ? {
                        required: true,
                        message: t('admin.ai_configs.api_key_required'),
                      }
                    : {},
                  { max: 4096 },
                ]}
              >
                <Input.Password className="mono" maxLength={4096} autoComplete="off" />
              </Form.Item>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
                <Form.Item
                  name="timeout_ms"
                  label={t('admin.ai_configs.field_timeout_ms')}
                  rules={[{ required: true, type: 'number', min: 1000, max: 600000 }]}
                >
                  <InputNumber className="mono" min={1000} max={600000} style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item
                  name="max_prompt_tokens"
                  label={t('admin.ai_configs.field_max_prompt_tokens')}
                  rules={[{ required: true, type: 'number', min: 100, max: 200000 }]}
                >
                  <InputNumber className="mono" min={100} max={200000} style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item
                  name="max_completion_tokens"
                  label={t('admin.ai_configs.field_max_completion_tokens')}
                  rules={[{ required: true, type: 'number', min: 100, max: 200000 }]}
                >
                  <InputNumber className="mono" min={100} max={200000} style={{ width: '100%' }} />
                </Form.Item>
              </div>
              <Form.Item
                name="fallback_priority"
                label={t('admin.ai_configs.field_fallback_priority')}
                extra={t('admin.ai_configs.fallback_priority_help')}
                rules={[{ type: 'number', min: 0, max: 100 }]}
              >
                <InputNumber className="mono" min={0} max={100} style={{ width: 200 }} />
              </Form.Item>
              <Form.Item
                name="system_prompt_template"
                label={t('admin.ai_configs.field_system_prompt')}
                extra={t('admin.ai_configs.system_prompt_help')}
                rules={[
                  { max: 20000 },
                  {
                    validator: (_, value?: string) =>
                      value && value.trim() && !value.includes('{{sql}}')
                        ? Promise.reject(new Error(t('admin.ai_configs.system_prompt_sql_required')))
                        : Promise.resolve(),
                  },
                ]}
              >
                <Input.TextArea className="mono" rows={12} maxLength={20000} autoComplete="off" />
              </Form.Item>
              <Button
                onClick={() => void loadDefaultPrompt()}
                loading={defaultPromptQuery.isFetching}
                style={{ marginBottom: 16 }}
              >
                {t('admin.ai_configs.system_prompt_load_default')}
              </Button>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <Form.Item
                  name="langfuse_prompt_name"
                  label={t('admin.ai_configs.field_langfuse_prompt_name')}
                  extra={t('admin.ai_configs.langfuse_prompt_help')}
                  rules={[{ max: 255 }]}
                >
                  <Input className="mono" maxLength={255} autoComplete="off" />
                </Form.Item>
                <Form.Item
                  name="langfuse_prompt_label"
                  label={t('admin.ai_configs.field_langfuse_prompt_label')}
                  rules={[{ max: 255 }]}
                >
                  <Input className="mono" maxLength={255} autoComplete="off" placeholder="production" />
                </Form.Item>
              </div>
              <RagFormSection form={form} />
              <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
                <Button onClick={() => setCurrentStep('provider')}>{t('common.back')}</Button>
                <Button type="primary" htmlType="submit" loading={createMutation.isPending}>
                  {t('admin.ai_configs.wizard.save_and_test')}
                </Button>
              </div>
            </Form>
          )}
          {currentStep === 'test' && createdId && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <Button
                icon={<PlayCircleOutlined />}
                onClick={() => testMutation.mutate()}
                loading={testMutation.isPending}
              >
                {t('admin.ai_configs.wizard.test_button')}
              </Button>
              {testResult && testResult.status === 'OK' && (
                <Result
                  status="success"
                  icon={<CheckOutlined />}
                  title={t('admin.ai_configs.test_ok')}
                  subTitle={testResult.detail}
                />
              )}
              {testResult && testResult.status === 'ERROR' && (
                <Result
                  status="error"
                  title={t('admin.ai_configs.test_failed_title')}
                  subTitle={testResult.detail}
                />
              )}
              <Button type="primary" onClick={() => navigate('/admin/ai-configs')}>
                {t('admin.ai_configs.wizard.finish')}
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
