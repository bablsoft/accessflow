import { useEffect, useState } from 'react';
import { App, Button, Form, Input, InputNumber, Skeleton, Switch } from 'antd';
import { CheckOutlined, LoadingOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  aiConfigKeys,
  getAiConfig,
  setupProgressKeys,
  testAiConfig,
  updateAiConfig,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import type { AiProvider, UpdateAiConfigInput } from '@/types/api';

const PROVIDERS: { id: AiProvider; label: string; desc: string; defaultModel: string; defaultEndpoint: string }[] = [
  { id: 'OPENAI', label: 'OpenAI', desc: 'GPT-4o, GPT-4o-mini', defaultModel: 'gpt-4o', defaultEndpoint: 'https://api.openai.com/v1' },
  { id: 'ANTHROPIC', label: 'Anthropic', desc: 'Claude Sonnet, Haiku', defaultModel: 'claude-sonnet-4-20250514', defaultEndpoint: 'https://api.anthropic.com/v1' },
  { id: 'OLLAMA', label: 'Ollama', desc: 'Self-hosted local models', defaultModel: 'llama3.1:70b', defaultEndpoint: 'http://localhost:11434/api' },
];

const MASK = '********';

interface AiConfigFormValues {
  provider: AiProvider;
  model: string;
  endpoint: string;
  api_key: string;
  timeout_ms: number;
  max_prompt_tokens: number;
  max_completion_tokens: number;
  enable_ai_default: boolean;
  auto_approve_low: boolean;
  block_critical: boolean;
  include_schema: boolean;
}

export function AIConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<AiConfigFormValues>();
  const [testing, setTesting] = useState<'idle' | 'running' | 'ok'>('idle');

  const cfgQuery = useQuery({
    queryKey: aiConfigKeys.current(),
    queryFn: getAiConfig,
  });

  // Hydrate the form whenever the server payload arrives.
  useEffect(() => {
    if (cfgQuery.data) {
      form.setFieldsValue({
        provider: cfgQuery.data.provider,
        model: cfgQuery.data.model,
        endpoint: cfgQuery.data.endpoint ?? '',
        api_key: cfgQuery.data.api_key ?? '',
        timeout_ms: cfgQuery.data.timeout_ms,
        max_prompt_tokens: cfgQuery.data.max_prompt_tokens,
        max_completion_tokens: cfgQuery.data.max_completion_tokens,
        enable_ai_default: cfgQuery.data.enable_ai_default,
        auto_approve_low: cfgQuery.data.auto_approve_low,
        block_critical: cfgQuery.data.block_critical,
        include_schema: cfgQuery.data.include_schema,
      });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpdateAiConfigInput) => updateAiConfig(payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: aiConfigKeys.all });
      void queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      message.success(t('admin.ai_config.save_success'));
    },
    onError: (err) => message.error(adminErrorMessage(err)),
  });

  const testMutation = useMutation({
    mutationFn: () => testAiConfig(),
    onMutate: () => {
      setTesting('running');
    },
    onSuccess: (result) => {
      if (result.status === 'OK') {
        setTesting('ok');
      } else {
        setTesting('idle');
        message.error(t('admin.ai_config.test_failed', { detail: result.detail }));
      }
    },
    onError: (err) => {
      setTesting('idle');
      message.error(adminErrorMessage(err));
    },
  });

  const onSave = (values: AiConfigFormValues) => {
    // Only send the API key if the user actually changed it (still showing the
    // masked placeholder = leave the existing ciphertext in place).
    const apiKey = values.api_key === MASK ? undefined : values.api_key;
    saveMutation.mutate({
      provider: values.provider,
      model: values.model.trim(),
      endpoint: values.endpoint.trim() || null,
      api_key: apiKey ?? null,
      timeout_ms: values.timeout_ms,
      max_prompt_tokens: values.max_prompt_tokens,
      max_completion_tokens: values.max_completion_tokens,
      enable_ai_default: values.enable_ai_default,
      auto_approve_low: values.auto_approve_low,
      block_critical: values.block_critical,
      include_schema: values.include_schema,
    });
  };

  if (cfgQuery.isLoading || !cfgQuery.data) {
    if (cfgQuery.isError) {
      return (
        <EmptyState
          title={t('admin.ai_config.load_error')}
          description={adminErrorMessage(cfgQuery.error)}
        />
      );
    }
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 12 }} />
      </div>
    );
  }

  const cfg = cfgQuery.data;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.ai_config.title')}
        subtitle={t('admin.ai_config.subtitle')}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28, maxWidth: 760 }}>
        <Form<AiConfigFormValues>
          form={form}
          layout="vertical"
          onFinish={onSave}
          initialValues={{
            provider: cfg.provider,
            model: cfg.model,
            endpoint: cfg.endpoint ?? '',
            api_key: cfg.api_key ?? '',
            timeout_ms: cfg.timeout_ms,
            max_prompt_tokens: cfg.max_prompt_tokens,
            max_completion_tokens: cfg.max_completion_tokens,
            enable_ai_default: cfg.enable_ai_default,
            auto_approve_low: cfg.auto_approve_low,
            block_critical: cfg.block_critical,
            include_schema: cfg.include_schema,
          }}
        >
          <Section title={t('admin.ai_config.section_provider')}>
            <Form.Item name="provider" noStyle>
              <ProviderCards onPick={(p) => {
                const def = PROVIDERS.find((x) => x.id === p)!;
                form.setFieldsValue({
                  provider: p,
                  model: def.defaultModel,
                  endpoint: def.defaultEndpoint,
                });
              }} />
            </Form.Item>
          </Section>

          <Section title={t('admin.ai_config.section_connection')}>
            <Grid>
              <Form.Item
                name="model"
                label={t('admin.ai_config.label_model')}
                rules={[{ required: true, max: 100 }]}
              >
                <Input className="mono" maxLength={100} />
              </Form.Item>
              <Form.Item
                name="endpoint"
                label={t('admin.ai_config.label_api_endpoint')}
                rules={[{ max: 500 }]}
              >
                <Input className="mono" maxLength={500} />
              </Form.Item>
              <Form.Item
                name="api_key"
                label={t('admin.ai_config.label_api_key')}
                rules={[{ max: 4096 }]}
              >
                <Input.Password className="mono" maxLength={4096} autoComplete="off" />
              </Form.Item>
              <Form.Item
                name="timeout_ms"
                label={t('admin.ai_config.label_timeout_ms')}
                rules={[{ required: true, type: 'number', min: 1000, max: 600000 }]}
              >
                <InputNumber className="mono" min={1000} max={600000} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="max_prompt_tokens"
                label={t('admin.ai_config.label_max_prompt_tokens')}
                rules={[{ required: true, type: 'number', min: 100, max: 200000 }]}
              >
                <InputNumber className="mono" min={100} max={200000} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="max_completion_tokens"
                label={t('admin.ai_config.label_max_completion_tokens')}
                rules={[{ required: true, type: 'number', min: 100, max: 200000 }]}
              >
                <InputNumber className="mono" min={100} max={200000} style={{ width: '100%' }} />
              </Form.Item>
            </Grid>
          </Section>

          <Section title={t('admin.ai_config.section_behavior')}>
            <Grid>
              <Form.Item
                name="enable_ai_default"
                label={t('admin.ai_config.label_enable_ai_default')}
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              <Form.Item
                name="auto_approve_low"
                label={t('admin.ai_config.label_auto_approve_low')}
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              <Form.Item
                name="block_critical"
                label={t('admin.ai_config.label_block_critical')}
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              <Form.Item
                name="include_schema"
                label={t('admin.ai_config.label_include_schema')}
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
            </Grid>
          </Section>

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
                ? t('admin.ai_config.test_ok_button')
                : t('admin.ai_config.test_button')}
            </Button>
          </div>
        </Form>
      </div>
    </div>
  );
}

function ProviderCards({
  value,
  onChange,
  onPick,
}: {
  value?: AiProvider;
  onChange?: (v: AiProvider) => void;
  onPick: (v: AiProvider) => void;
}) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
      {PROVIDERS.map((p) => {
        const active = value === p.id;
        return (
          <button
            key={p.id}
            type="button"
            onClick={() => {
              onChange?.(p.id);
              onPick(p.id);
            }}
            style={{
              padding: 14,
              textAlign: 'left',
              background: active ? 'var(--accent-bg)' : 'var(--bg-elev)',
              border: `1px solid ${active ? 'var(--accent)' : 'var(--border)'}`,
              borderRadius: 8,
              cursor: 'pointer',
              color: 'var(--fg)',
            }}
          >
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 4 }}>{p.label}</div>
            <div className="muted" style={{ fontSize: 11 }}>
              {p.desc}
            </div>
          </button>
        );
      })}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 32 }}>
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 14 }}>{title}</div>
      {children}
    </div>
  );
}

function Grid({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>{children}</div>
  );
}
