import { Form, Input, InputNumber, Select, Switch } from 'antd';
import type { FormInstance } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  EMBEDDING_PROVIDERS,
  RAG_STORE_TYPES,
  aiProviderLabel,
  enumOptions,
  ragStoreTypeLabel,
} from '@/utils/enumLabels';
import type { AiProvider, RagStoreType } from '@/types/api';

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
  const showEmbeddingEndpoint =
    embeddingProvider === 'OLLAMA' ||
    embeddingProvider === 'OPENAI_COMPATIBLE' ||
    embeddingProvider === 'HUGGING_FACE';

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
          <div style={GRID_TWO}>
            <Form.Item
              name="rag_store_type"
              label={t('admin.ai_configs.rag.field_store_type')}
              rules={[{ required: true, message: t('admin.ai_configs.rag.store_type_required') }]}
            >
              <Select
                options={enumOptions(RAG_STORE_TYPES, ragStoreTypeLabel, t)}
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
          <Form.Item
            name="embedding_api_key"
            label={t('admin.ai_configs.rag.field_embedding_api_key')}
            rules={[{ max: 4096 }]}
          >
            <Input.Password className="mono" maxLength={4096} autoComplete="off" />
          </Form.Item>
        </>
      )}
    </div>
  );
}
