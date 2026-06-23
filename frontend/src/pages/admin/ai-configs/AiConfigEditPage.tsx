import { useEffect, useState } from 'react';
import { App, Button, Form, Input, InputNumber, Skeleton, Tag } from 'antd';
import {
  ArrowLeftOutlined,
  CheckOutlined,
  LoadingOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  aiConfigKeys,
  getAiConfig,
  getDefaultAiPrompt,
  setupProgressKeys,
  testAiConfig,
  updateAiConfig,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { aiProviderLabel } from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import type {
  AiConfigModel,
  AiProvider,
  RagStoreType,
  UpdateAiConfigInput,
  VotingStrategy,
} from '@/types/api';
import { RagFormSection } from './RagFormSection';
import { OrchestrationFormSection } from './OrchestrationFormSection';
import { GuardrailsFormSection } from './GuardrailsFormSection';
import { KnowledgeDocumentsSection } from './KnowledgeDocumentsSection';

const MASK = '********';

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
  orchestration_enabled: boolean;
  voting_strategy: VotingStrategy;
  voting_weight: number;
  guardrail_patterns: string[];
  models: AiConfigModel[];
}

export default function AiConfigEditPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const [testing, setTesting] = useState<'idle' | 'running' | 'ok'>('idle');

  const cfgQuery = useQuery({
    queryKey: id ? aiConfigKeys.detail(id) : ['aiConfig', 'detail', 'none'],
    queryFn: () => {
      if (!id) throw new Error('Missing id');
      return getAiConfig(id);
    },
    enabled: Boolean(id),
  });

  useEffect(() => {
    if (cfgQuery.data) {
      const showsEndpoint =
        cfgQuery.data.provider === 'OLLAMA' ||
        cfgQuery.data.provider === 'OPENAI_COMPATIBLE' ||
        cfgQuery.data.provider === 'HUGGING_FACE';
      form.setFieldsValue({
        name: cfgQuery.data.name,
        model: cfgQuery.data.model,
        endpoint: showsEndpoint ? (cfgQuery.data.endpoint ?? '') : '',
        api_key: cfgQuery.data.api_key ?? '',
        timeout_ms: cfgQuery.data.timeout_ms,
        max_prompt_tokens: cfgQuery.data.max_prompt_tokens,
        max_completion_tokens: cfgQuery.data.max_completion_tokens,
        system_prompt_template: cfgQuery.data.system_prompt_template ?? '',
        langfuse_prompt_name: cfgQuery.data.langfuse_prompt_name ?? '',
        langfuse_prompt_label: cfgQuery.data.langfuse_prompt_label ?? '',
        rag_enabled: cfgQuery.data.rag_enabled,
        rag_store_type: cfgQuery.data.rag_store_type,
        rag_top_k: cfgQuery.data.rag_top_k,
        rag_similarity_threshold: cfgQuery.data.rag_similarity_threshold,
        rag_endpoint: cfgQuery.data.rag_endpoint ?? '',
        rag_collection: cfgQuery.data.rag_collection ?? '',
        rag_api_key: cfgQuery.data.rag_api_key ?? '',
        embedding_provider: cfgQuery.data.embedding_provider,
        embedding_model: cfgQuery.data.embedding_model ?? '',
        embedding_endpoint: cfgQuery.data.embedding_endpoint ?? '',
        embedding_api_key: cfgQuery.data.embedding_api_key ?? '',
        orchestration_enabled: cfgQuery.data.orchestration_enabled,
        voting_strategy: cfgQuery.data.voting_strategy,
        voting_weight: cfgQuery.data.voting_weight,
        guardrail_patterns: cfgQuery.data.guardrail_patterns,
        models: cfgQuery.data.models,
      });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (input: UpdateAiConfigInput) => {
      if (!id) throw new Error('Missing id');
      return updateAiConfig(id, input);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: aiConfigKeys.all });
      void queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      message.success(t('admin.ai_configs.save_success'));
    },
    onError: (err: unknown) => showApiError(message, err, adminErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: () => {
      if (!id) throw new Error('Missing id');
      return testAiConfig(id);
    },
    onMutate: () => setTesting('running'),
    onSuccess: (result) => {
      if (result.status === 'OK') {
        setTesting('ok');
      } else {
        setTesting('idle');
        message.error(t('admin.ai_configs.test_failed', { detail: result.detail }));
      }
    },
    onError: (err: unknown) => {
      setTesting('idle');
      showApiError(message, err, adminErrorMessage);
    },
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

  if (cfgQuery.isLoading || !cfgQuery.data) {
    if (cfgQuery.isError) {
      return (
        <EmptyState
          title={t('admin.ai_configs.load_error')}
          description={adminErrorMessage(cfgQuery.error)}
        />
      );
    }
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  }

  const cfg = cfgQuery.data;

  const needsEndpoint =
    cfg.provider === 'OLLAMA' ||
    cfg.provider === 'OPENAI_COMPATIBLE' ||
    cfg.provider === 'HUGGING_FACE';

  const onSave = (values: FormValues) => {
    const apiKey = values.api_key === MASK ? undefined : values.api_key;
    const ragApiKey = values.rag_api_key === MASK ? undefined : values.rag_api_key;
    const embeddingApiKey = values.embedding_api_key === MASK ? undefined : values.embedding_api_key;
    const isQdrant = values.rag_enabled && values.rag_store_type === 'QDRANT';
    saveMutation.mutate({
      name: values.name.trim(),
      model: values.model.trim(),
      endpoint: needsEndpoint ? (values.endpoint?.trim() || null) : null,
      api_key: apiKey ?? null,
      timeout_ms: values.timeout_ms,
      max_prompt_tokens: values.max_prompt_tokens,
      max_completion_tokens: values.max_completion_tokens,
      system_prompt_template: values.system_prompt_template?.trim() ? values.system_prompt_template : '',
      langfuse_prompt_name: values.langfuse_prompt_name?.trim() || '',
      langfuse_prompt_label: values.langfuse_prompt_label?.trim() || '',
      rag_enabled: values.rag_enabled,
      rag_store_type: values.rag_enabled ? values.rag_store_type : null,
      rag_top_k: values.rag_top_k,
      rag_similarity_threshold: values.rag_similarity_threshold,
      rag_endpoint: isQdrant ? (values.rag_endpoint?.trim() || null) : null,
      rag_collection: isQdrant ? (values.rag_collection?.trim() || null) : null,
      rag_api_key: ragApiKey ?? null,
      embedding_provider: values.rag_enabled ? values.embedding_provider : null,
      embedding_model: values.rag_enabled ? (values.embedding_model?.trim() || null) : null,
      embedding_endpoint: values.embedding_endpoint?.trim() || null,
      embedding_api_key: embeddingApiKey ?? null,
      orchestration_enabled: values.orchestration_enabled,
      voting_strategy: values.voting_strategy,
      voting_weight: values.voting_weight,
      guardrail_patterns: (values.guardrail_patterns ?? []).filter((p) => p?.trim()),
      models: (values.models ?? []).map((m) => ({
        id: m.id,
        provider: m.provider,
        model: m.model?.trim() ?? '',
        endpoint: m.endpoint?.trim() || null,
        api_key: m.api_key === MASK ? undefined : (m.api_key ?? null),
        weight: m.weight ?? 1,
        enabled: m.enabled ?? true,
      })),
    });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.ai_configs.edit_title', { name: cfg.name })}
        subtitle={
          <span>
            <Tag>{aiProviderLabel(t, cfg.provider)}</Tag>
            <span className="mono muted" style={{ marginLeft: 8 }}>
              {cfg.model}
            </span>
          </span>
        }
        actions={
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/admin/ai-configs')}>
            {t('common.back')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28, maxWidth: 760 }}>
        <Form<FormValues> form={form} layout="vertical" onFinish={onSave}>
          <Form.Item
            name="name"
            label={t('admin.ai_configs.field_name')}
            rules={[{ required: true }, { max: 255 }]}
          >
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item
            name="model"
            label={t('admin.ai_configs.field_model')}
            rules={[{ required: true }, { max: 100 }]}
          >
            <Input className="mono" maxLength={100} />
          </Form.Item>
          {needsEndpoint && (
            <Form.Item
              name="endpoint"
              label={t('admin.ai_configs.field_endpoint')}
              rules={[
                cfg.provider === 'OPENAI_COMPATIBLE'
                  ? { required: true, message: t('admin.ai_configs.endpoint_required') }
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
            rules={[{ max: 4096 }]}
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
            <Input.TextArea className="mono" rows={14} maxLength={20000} autoComplete="off" />
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
          <OrchestrationFormSection form={form} />
          <GuardrailsFormSection />
          <div
            style={{
              display: 'flex',
              gap: 8,
              paddingTop: 16,
              borderTop: '1px solid var(--border)',
            }}
          >
            <Button
              type="primary"
              icon={<CheckOutlined />}
              htmlType="submit"
              loading={saveMutation.isPending}
            >
              {t('common.save')}
            </Button>
            <Button
              icon={
                testing === 'running' ? (
                  <LoadingOutlined />
                ) : testing === 'ok' ? (
                  <CheckOutlined style={{ color: 'var(--risk-low)' }} />
                ) : (
                  <PlayCircleOutlined />
                )
              }
              onClick={() => testMutation.mutate()}
              loading={testMutation.isPending}
            >
              {testing === 'ok'
                ? t('admin.ai_configs.test_ok_button')
                : t('admin.ai_configs.test_button')}
            </Button>
          </div>
        </Form>
        <KnowledgeDocumentsSection configId={cfg.id} ragEnabled={cfg.rag_enabled} />
      </div>
    </div>
  );
}
