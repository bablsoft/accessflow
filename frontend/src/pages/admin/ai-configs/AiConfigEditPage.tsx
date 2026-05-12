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
  setupProgressKeys,
  testAiConfig,
  updateAiConfig,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { UpdateAiConfigInput } from '@/types/api';

const MASK = '********';

interface FormValues {
  name: string;
  model: string;
  endpoint: string;
  api_key: string;
  timeout_ms: number;
  max_prompt_tokens: number;
  max_completion_tokens: number;
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
      const isOllama = cfgQuery.data.provider === 'OLLAMA';
      form.setFieldsValue({
        name: cfgQuery.data.name,
        model: cfgQuery.data.model,
        endpoint: isOllama ? (cfgQuery.data.endpoint ?? '') : '',
        api_key: cfgQuery.data.api_key ?? '',
        timeout_ms: cfgQuery.data.timeout_ms,
        max_prompt_tokens: cfgQuery.data.max_prompt_tokens,
        max_completion_tokens: cfgQuery.data.max_completion_tokens,
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

  const onSave = (values: FormValues) => {
    const apiKey = values.api_key === MASK ? undefined : values.api_key;
    const isOllama = cfg.provider === 'OLLAMA';
    saveMutation.mutate({
      name: values.name.trim(),
      model: values.model.trim(),
      endpoint: isOllama ? (values.endpoint?.trim() || null) : null,
      api_key: apiKey ?? null,
      timeout_ms: values.timeout_ms,
      max_prompt_tokens: values.max_prompt_tokens,
      max_completion_tokens: values.max_completion_tokens,
    });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.ai_configs.edit_title', { name: cfg.name })}
        subtitle={
          <span>
            <Tag>{cfg.provider}</Tag>
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
          {cfg.provider === 'OLLAMA' && (
            <Form.Item
              name="endpoint"
              label={t('admin.ai_configs.field_endpoint')}
              rules={[{ max: 500 }]}
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
      </div>
    </div>
  );
}
