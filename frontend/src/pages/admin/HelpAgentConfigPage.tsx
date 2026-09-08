import { useEffect } from 'react';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Form,
  InputNumber,
  Select,
  Skeleton,
  Switch,
  Tag,
} from 'antd';
import { ApiOutlined, CheckOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  getHelpAgentConfig,
  helpAgentKeys,
  reindexHelpCorpus,
  testHelpAgentConfig,
  updateHelpAgentConfig,
} from '@/api/helpAgent';
import { aiConfigKeys, getRagCapabilities, listAiConfigs } from '@/api/admin';
import { fetchHelpChatAvailability, helpChatKeys } from '@/api/helpChat';
import { retrievalReadiness } from './helpAgentRetrieval';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import type { UpdateHelpAgentConfigInput } from '@/types/api';

interface HelpAgentFormValues {
  enabled: boolean;
  ai_config_id?: string | null;
  retrieval_enabled: boolean;
  top_k: number;
  similarity_threshold: number;
  max_history_turns: number;
  max_question_chars: number;
  send_user_context: boolean;
  retention_days: number;
  per_user_requests_per_minute: number;
}

export function HelpAgentConfigPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<HelpAgentFormValues>();

  const cfgQuery = useQuery({
    queryKey: helpAgentKeys.config(),
    queryFn: getHelpAgentConfig,
  });
  const aiConfigsQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: listAiConfigs,
  });
  const capabilitiesQuery = useQuery({
    queryKey: aiConfigKeys.ragCapabilities(),
    queryFn: getRagCapabilities,
  });
  // The bundled corpus this build ships, which only the availability endpoint reports. Shared
  // cache entry with the help launcher, so an admin with the panel open pays nothing extra.
  const availabilityQuery = useQuery({
    queryKey: helpChatKeys.availability(),
    queryFn: fetchHelpChatAvailability,
  });

  useEffect(() => {
    if (cfgQuery.data) {
      form.setFieldsValue({
        enabled: cfgQuery.data.enabled,
        ai_config_id: cfgQuery.data.ai_config_id ?? null,
        retrieval_enabled: cfgQuery.data.retrieval_enabled,
        top_k: cfgQuery.data.top_k,
        similarity_threshold: cfgQuery.data.similarity_threshold,
        max_history_turns: cfgQuery.data.max_history_turns,
        max_question_chars: cfgQuery.data.max_question_chars,
        send_user_context: cfgQuery.data.send_user_context,
        retention_days: cfgQuery.data.retention_days,
        per_user_requests_per_minute: cfgQuery.data.per_user_requests_per_minute,
      });
    }
  }, [cfgQuery.data, form]);

  const saveMutation = useMutation({
    mutationFn: (payload: UpdateHelpAgentConfigInput) => updateHelpAgentConfig(payload),
    onSuccess: (saved) => {
      queryClient.setQueryData(helpAgentKeys.config(), saved);
      message.success(t('admin.help_agent.save_success'));
    },
    onError: (err) =>
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('admin.help_agent.save_error'))),
  });

  const testMutation = useMutation({
    mutationFn: testHelpAgentConfig,
    onSuccess: (result) => {
      if (result.status === 'OK') message.success(result.detail);
      else message.error(result.detail);
    },
    onError: (err) =>
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('admin.help_agent.test_error'))),
  });

  const reindexMutation = useMutation({
    mutationFn: reindexHelpCorpus,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: helpAgentKeys.all });
      message.success(t('admin.help_agent.reindex_accepted'));
    },
    onError: (err) =>
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('admin.help_agent.reindex_error')),
      ),
  });

  const selectedAiConfigId = Form.useWatch('ai_config_id', form);
  const selectedAiConfig = aiConfigsQuery.data?.find((c) => c.id === selectedAiConfigId);
  const readiness = retrievalReadiness(
    selectedAiConfig,
    capabilitiesQuery.data?.pgvector_available ?? false,
  );

  const onFinish = (values: HelpAgentFormValues) => {
    const aiConfigId = values.ai_config_id ?? null;
    saveMutation.mutate({
      enabled: values.enabled,
      // `null` means "unchanged" on a partial update, so unbinding needs the explicit flag.
      ...(aiConfigId === null ? { clear_ai_config: true } : { ai_config_id: aiConfigId }),
      retrieval_enabled: values.retrieval_enabled,
      top_k: values.top_k,
      similarity_threshold: values.similarity_threshold,
      max_history_turns: values.max_history_turns,
      max_question_chars: values.max_question_chars,
      send_user_context: values.send_user_context,
      retention_days: values.retention_days,
      per_user_requests_per_minute: values.per_user_requests_per_minute,
    });
  };

  if (cfgQuery.isLoading) {
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  }
  if (cfgQuery.isError) {
    return (
      <EmptyState
        title={t('admin.help_agent.load_error')}
        description={apiErrorMessage(cfgQuery.error, () => t('admin.help_agent.load_error'))}
      />
    );
  }

  const config = cfgQuery.data;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* No docsAnchor yet: the public docs section for the help agent lands with the
          documentation sweep (#908), and config/__tests__/docs.test.ts requires the anchor and the
          website heading to ship together. */}
      <PageHeader
        title={t('admin.help_agent.title')}
        subtitle={t('admin.help_agent.subtitle')}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
        {readiness === 'ready' ? null : (
          <Alert
            style={{ marginBottom: 24 }}
            type="info"
            showIcon
            title={t(`admin.help_agent.retrieval_unavailable.${readiness}`)}
            description={t('admin.help_agent.retrieval_unavailable.quick_reference')}
          />
        )}
        {config?.index_error ? (
          <Alert
            style={{ marginBottom: 24 }}
            type="warning"
            showIcon
            title={t('admin.help_agent.index_error')}
            description={config.index_error}
          />
        ) : null}

        <Form<HelpAgentFormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Section title={t('admin.help_agent.section_agent')}>
            <Form.Item
              name="enabled"
              label={t('admin.help_agent.label_enabled')}
              extra={t('admin.help_agent.enabled_help')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="ai_config_id"
              label={t('admin.help_agent.label_ai_config')}
              extra={t('admin.help_agent.ai_config_help')}
            >
              <Select
                allowClear
                loading={aiConfigsQuery.isLoading}
                placeholder={t('admin.help_agent.ai_config_placeholder')}
                options={(aiConfigsQuery.data ?? []).map((c) => ({
                  value: c.id,
                  label: `${c.name} — ${c.model}`,
                }))}
              />
            </Form.Item>
            <Form.Item
              name="send_user_context"
              label={t('admin.help_agent.label_send_user_context')}
              extra={t('admin.help_agent.send_user_context_help')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </Section>

          <Section title={t('admin.help_agent.section_retrieval')}>
            <Form.Item
              name="retrieval_enabled"
              label={t('admin.help_agent.label_retrieval_enabled')}
              extra={t('admin.help_agent.retrieval_enabled_help')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Grid>
              <Form.Item
                name="top_k"
                label={t('admin.help_agent.label_top_k')}
                rules={[{ required: true, type: 'number', min: 1, max: 20 }]}
              >
                <InputNumber min={1} max={20} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="similarity_threshold"
                label={t('admin.help_agent.label_similarity_threshold')}
                rules={[{ required: true, type: 'number', min: 0, max: 1 }]}
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
            </Grid>
          </Section>

          <Section title={t('admin.help_agent.section_conversation')}>
            <Grid>
              <Form.Item
                name="max_history_turns"
                label={t('admin.help_agent.label_max_history_turns')}
                rules={[{ required: true, type: 'number', min: 1, max: 50 }]}
              >
                <InputNumber min={1} max={50} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="max_question_chars"
                label={t('admin.help_agent.label_max_question_chars')}
                rules={[{ required: true, type: 'number', min: 100, max: 10000 }]}
              >
                <InputNumber min={100} max={10000} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="retention_days"
                label={t('admin.help_agent.label_retention_days')}
                extra={t('admin.help_agent.retention_days_help')}
                rules={[{ required: true, type: 'number', min: 1, max: 3650 }]}
              >
                <InputNumber min={1} max={3650} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="per_user_requests_per_minute"
                label={t('admin.help_agent.label_requests_per_minute')}
                rules={[{ required: true, type: 'number', min: 1, max: 120 }]}
              >
                <InputNumber min={1} max={120} style={{ width: '100%' }} />
              </Form.Item>
            </Grid>
          </Section>

          <Section title={t('admin.help_agent.section_corpus')}>
            <Descriptions
              size="small"
              column={1}
              bordered
              items={[
                {
                  key: 'version',
                  label: t('admin.help_agent.corpus_version'),
                  children: config?.indexed_corpus_version ? (
                    <Tag className="mono">{config.indexed_corpus_version}</Tag>
                  ) : (
                    t('admin.help_agent.corpus_never_indexed')
                  ),
                },
                {
                  key: 'bundled',
                  label: t('admin.help_agent.corpus_bundled'),
                  children: availabilityQuery.data?.corpus_version ? (
                    <span>
                      <Tag className="mono">{availabilityQuery.data.corpus_version}</Tag>
                      {t('admin.help_agent.corpus_chunks', {
                        count: availabilityQuery.data.chunk_count,
                      })}
                    </span>
                  ) : (
                    t('admin.help_agent.corpus_unavailable')
                  ),
                },
                {
                  key: 'indexed_at',
                  label: t('admin.help_agent.corpus_indexed_at'),
                  children: config?.indexed_at
                    ? fmtDate(config.indexed_at)
                    : t('admin.help_agent.corpus_never'),
                },
                {
                  key: 'status',
                  label: t('admin.help_agent.corpus_status'),
                  children: config?.index_error
                    ? t('admin.help_agent.corpus_status_error')
                    : t('admin.help_agent.corpus_status_ok'),
                },
              ]}
            />
            <div style={{ marginTop: 12 }}>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => reindexMutation.mutate()}
                loading={reindexMutation.isPending}
              >
                {t('admin.help_agent.reindex_button')}
              </Button>
            </div>
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
              {t('admin.help_agent.save_button')}
            </Button>
            <Button
              icon={<ApiOutlined />}
              onClick={() => testMutation.mutate()}
              loading={testMutation.isPending}
            >
              {t('admin.help_agent.test_button')}
            </Button>
          </div>
        </Form>
      </div>
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

export default HelpAgentConfigPage;
