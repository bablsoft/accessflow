import { Alert, Form, Input, InputNumber, Select, Switch } from 'antd';
import type { FormInstance } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { aiConfigKeys, getRagCapabilities } from '@/api/admin';
import {
  DIMENSION_CAPABLE_PROVIDERS,
  EMBEDDING_PROVIDERS,
  RAG_STORE_TYPES,
  VOYAGE_DIMENSIONS,
  aiProviderLabel,
  enumOptions,
  ragStoreTypeLabel,
} from '@/utils/enumLabels';
import type { AiProvider, RagStoreType } from '@/types/api';

/**
 * Voyage AI is a third party, not Anthropic — Anthropic publishes no embeddings API and recommends
 * Voyage, reached with its own key. Prefilling the endpoint and model saves the admin a trip to
 * Voyage's docs for values that never vary.
 */
const VOYAGE_DEFAULTS = {
  endpoint: 'https://api.voyageai.com/v1',
  model: 'voyage-4',
  dimensions: 1024,
} as const;

const GRID_TWO: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 16,
};

/**
 * RAG / embedding fields rendered inside a parent AntD Form (shared by the create wizard and the
 * edit page). Required rules only fire when the dependent field is mounted, so disabling RAG (or
 * choosing the in-app store) does not block submission.
 */
export function RagFormSection({ form }: { form: FormInstance }) {
  const { t } = useTranslation();
  const enabled = Form.useWatch('rag_enabled', form) as boolean | undefined;
  const storeType = Form.useWatch('rag_store_type', form) as RagStoreType | undefined;
  const embeddingProvider = Form.useWatch('embedding_provider', form) as AiProvider | undefined;
  const isVoyage = embeddingProvider === 'VOYAGE';
  // The field must be mounted for every provider that can honour a length, because an unmounted
  // Form.Item is absent from onFinish's values and the edit page cannot then tell "unchanged" from
  // "cleared" — it would wipe a dimension set over the API on any unrelated save.
  const showDimensions =
    embeddingProvider !== undefined && DIMENSION_CAPABLE_PROVIDERS.includes(embeddingProvider);
  const showEmbeddingEndpoint =
    embeddingProvider === 'OLLAMA' ||
    embeddingProvider === 'OPENAI_COMPATIBLE' ||
    embeddingProvider === 'HUGGING_FACE' ||
    isVoyage;
  const { data: capabilities } = useQuery({
    queryKey: aiConfigKeys.ragCapabilities(),
    queryFn: getRagCapabilities,
  });
  // Assume available while loading so the warning does not flash before the answer arrives.
  const pgvectorAvailable = capabilities?.pgvector_available ?? true;
  const storeTypeOptions = enumOptions(RAG_STORE_TYPES, ragStoreTypeLabel, t).map((option) =>
    option.value === 'PGVECTOR' && !pgvectorAvailable ? { ...option, disabled: true } : option,
  );

  return (
    <div style={{ borderTop: '1px solid var(--border)', paddingTop: 16, marginTop: 8 }}>
      <h3 style={{ marginBottom: 4 }}>{t('admin.ai_configs.rag.section_title')}</h3>
      <p className="muted" style={{ marginTop: 0 }}>
        {t('admin.ai_configs.rag.section_help')}
      </p>
      <Form.Item
        name="rag_enabled"
        label={t('admin.ai_configs.rag.enabled')}
        valuePropName="checked"
      >
        <Switch />
      </Form.Item>
      {enabled && (
        <>
          {!pgvectorAvailable && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              title={t('admin.ai_configs.rag.pgvector_unavailable_warning')}
              description={t('admin.ai_configs.rag.pgvector_unavailable_help')}
            />
          )}
          <div style={GRID_TWO}>
            <Form.Item
              name="rag_store_type"
              label={t('admin.ai_configs.rag.field_store_type')}
              rules={[{ required: true, message: t('admin.ai_configs.rag.store_type_required') }]}
            >
              <Select
                options={storeTypeOptions}
                placeholder={t('admin.ai_configs.rag.field_store_type')}
              />
            </Form.Item>
            <Form.Item
              name="rag_top_k"
              label={t('admin.ai_configs.rag.field_top_k')}
              rules={[
                { type: 'number', min: 1, max: 20, message: t('admin.ai_configs.rag.top_k_range') },
              ]}
            >
              <InputNumber className="mono" min={1} max={20} style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <Form.Item
            name="rag_similarity_threshold"
            label={t('admin.ai_configs.rag.field_threshold')}
            rules={[
              {
                type: 'number',
                min: 0,
                max: 1,
                message: t('admin.ai_configs.rag.threshold_range'),
              },
            ]}
          >
            <InputNumber className="mono" min={0} max={1} step={0.05} style={{ width: '100%' }} />
          </Form.Item>
          {storeType === 'QDRANT' && (
            <>
              <Form.Item
                name="rag_endpoint"
                label={t('admin.ai_configs.rag.field_endpoint')}
                rules={[
                  { required: true, message: t('admin.ai_configs.rag.endpoint_required') },
                  { max: 500 },
                ]}
              >
                <Input className="mono" maxLength={500} placeholder="http://qdrant:6334" />
              </Form.Item>
              <div style={GRID_TWO}>
                <Form.Item
                  name="rag_collection"
                  label={t('admin.ai_configs.rag.field_collection')}
                  rules={[
                    { required: true, message: t('admin.ai_configs.rag.collection_required') },
                    { max: 255 },
                  ]}
                >
                  <Input className="mono" maxLength={255} />
                </Form.Item>
                <Form.Item
                  name="rag_api_key"
                  label={t('admin.ai_configs.rag.field_api_key')}
                  rules={[{ max: 4096 }]}
                >
                  <Input.Password className="mono" maxLength={4096} autoComplete="off" />
                </Form.Item>
              </div>
            </>
          )}
          <h4 style={{ marginBottom: 8 }}>{t('admin.ai_configs.rag.embedding_section')}</h4>
          <div style={GRID_TWO}>
            <Form.Item
              name="embedding_provider"
              label={t('admin.ai_configs.rag.field_embedding_provider')}
              rules={[
                { required: true, message: t('admin.ai_configs.rag.embedding_provider_required') },
              ]}
            >
              <Select
                options={enumOptions(EMBEDDING_PROVIDERS, aiProviderLabel, t)}
                placeholder={t('admin.ai_configs.rag.field_embedding_provider')}
                onChange={(value: AiProvider) => {
                  if (value === 'VOYAGE') {
                    form.setFieldsValue({
                      embedding_endpoint: VOYAGE_DEFAULTS.endpoint,
                      embedding_model: VOYAGE_DEFAULTS.model,
                      embedding_dimensions: VOYAGE_DEFAULTS.dimensions,
                    });
                    return;
                  }
                  // Switching away has to undo the prefill, or a Voyage model name and base URL get
                  // saved against a provider that has never heard of them. Only the values this
                  // component wrote are cleared — anything the admin typed is left alone.
                  const cleared: Record<string, unknown> = { embedding_dimensions: null };
                  if (form.getFieldValue('embedding_model') === VOYAGE_DEFAULTS.model) {
                    cleared.embedding_model = '';
                  }
                  if (form.getFieldValue('embedding_endpoint') === VOYAGE_DEFAULTS.endpoint) {
                    cleared.embedding_endpoint = '';
                  }
                  form.setFieldsValue(cleared);
                }}
              />
            </Form.Item>
            <Form.Item
              name="embedding_model"
              label={t('admin.ai_configs.rag.field_embedding_model')}
              rules={[
                { required: true, message: t('admin.ai_configs.rag.embedding_model_required') },
                { max: 100 },
              ]}
            >
              <Input className="mono" maxLength={100} placeholder="text-embedding-3-small" />
            </Form.Item>
          </div>
          {showEmbeddingEndpoint && (
            <Form.Item
              name="embedding_endpoint"
              label={t('admin.ai_configs.rag.field_embedding_endpoint')}
              rules={[{ max: 500 }]}
            >
              <Input className="mono" maxLength={500} />
            </Form.Item>
          )}
          {showDimensions && (
            <Form.Item
              name="embedding_dimensions"
              label={t('admin.ai_configs.rag.field_embedding_dimensions')}
              extra={
                isVoyage
                  ? t('admin.ai_configs.rag.embedding_dimensions_voyage_help')
                  : t('admin.ai_configs.rag.embedding_dimensions_help')
              }
              rules={[
                {
                  type: 'number',
                  min: 0,
                  max: 4096,
                  message: t('admin.ai_configs.rag.embedding_dimensions_range'),
                },
              ]}
            >
              {isVoyage ? (
                // Voyage emits only these four widths, so offer exactly those rather than a number
                // the backend would reject.
                <Select
                  allowClear
                  options={VOYAGE_DIMENSIONS.map((value) => ({ value, label: String(value) }))}
                  placeholder={t('admin.ai_configs.rag.embedding_dimensions_placeholder')}
                />
              ) : (
                <InputNumber
                  className="mono"
                  min={1}
                  max={4096}
                  style={{ width: '100%' }}
                  placeholder={t('admin.ai_configs.rag.embedding_dimensions_placeholder')}
                />
              )}
            </Form.Item>
          )}
          {isVoyage && storeType === 'PGVECTOR' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              title={t('admin.ai_configs.rag.voyage_pgvector_warning')}
              description={t('admin.ai_configs.rag.voyage_pgvector_help')}
            />
          )}
          <Form.Item
            name="embedding_api_key"
            label={t('admin.ai_configs.rag.field_embedding_api_key')}
            extra={isVoyage ? t('admin.ai_configs.rag.voyage_api_key_help') : undefined}
            rules={[{ max: 4096 }]}
          >
            <Input.Password className="mono" maxLength={4096} autoComplete="off" />
          </Form.Item>
        </>
      )}
    </div>
  );
}
