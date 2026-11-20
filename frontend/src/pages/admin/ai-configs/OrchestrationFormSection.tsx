import { Button, Form, Input, InputNumber, Select, Switch } from 'antd';
import type { FormInstance } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  ORCHESTRATION_PROVIDERS,
  VOTING_STRATEGIES,
  aiProviderLabel,
  enumOptions,
  votingStrategyLabel,
} from '@/utils/enumLabels';
import type { AiProvider } from '@/types/api';

const GRID_TWO: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gap: 16,
};

/**
 * Multi-model orchestration + voting fields rendered inside a parent AntD Form (AF-450). When
 * orchestration is enabled, the primary model votes alongside the listed members; each member
 * carries its own provider/model/endpoint/key + weight. Required rules only fire when the section is
 * expanded, so disabling orchestration never blocks submission.
 */
export function OrchestrationFormSection({ form }: { form: FormInstance }) {
  const { t } = useTranslation();
  const enabled = Form.useWatch('orchestration_enabled', form) as boolean | undefined;

  return (
    <div className="u-mt-2" style={{ borderTop: '1px solid var(--border)', paddingTop: 16 }}>
      <h3 className="u-mb-1">{t('admin.ai_configs.orchestration.section_title')}</h3>
      <p className="muted u-mt-0">
        {t('admin.ai_configs.orchestration.section_help')}
      </p>
      <Form.Item
        name="orchestration_enabled"
        label={t('admin.ai_configs.orchestration.enabled')}
        valuePropName="checked"
      >
        <Switch />
      </Form.Item>
      {enabled && (
        <>
          <div style={GRID_TWO}>
            <Form.Item
              name="voting_strategy"
              label={t('admin.ai_configs.orchestration.field_voting_strategy')}
            >
              <Select options={enumOptions(VOTING_STRATEGIES, votingStrategyLabel, t)} />
            </Form.Item>
            <Form.Item
              name="voting_weight"
              label={t('admin.ai_configs.orchestration.field_voting_weight')}
              extra={t('admin.ai_configs.orchestration.voting_weight_help')}
              rules={[
                {
                  validator: (_, value?: number) =>
                    value === undefined || value === null || value > 0
                      ? Promise.resolve()
                      : Promise.reject(
                          new Error(t('admin.ai_configs.orchestration.weight_positive')),
                        ),
                },
              ]}
            >
              <InputNumber className="mono u-w-full" min={0} step={0.5} />
            </Form.Item>
          </div>
          <h4 className="u-mb-2">
            {t('admin.ai_configs.orchestration.members_section')}
          </h4>
          <Form.List name="models">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <MemberRow
                    key={field.key}
                    field={field}
                    form={form}
                    onRemove={() => remove(field.name)}
                  />
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() =>
                    add({ provider: 'OPENAI', model: '', weight: 1, enabled: true })
                  }
                >
                  {t('admin.ai_configs.orchestration.add_member')}
                </Button>
              </>
            )}
          </Form.List>
        </>
      )}
    </div>
  );
}

function MemberRow({
  field,
  form,
  onRemove,
}: {
  field: { key: number; name: number };
  form: FormInstance;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const provider = Form.useWatch(['models', field.name, 'provider'], form) as
    | AiProvider
    | undefined;
  const needsEndpoint =
    provider === 'OLLAMA' || provider === 'OPENAI_COMPATIBLE' || provider === 'HUGGING_FACE';

  return (
    <div
      className="u-p-3 u-mb-3" style={{ border: '1px solid var(--border)', borderRadius: 8 }}
      data-testid="orchestration-member"
    >
      <div className="u-flex u-justify-between u-items-center">
        <div className="u-flex-1" style={{ ...GRID_TWO }}>
          <Form.Item
            name={[field.name, 'provider']}
            label={t('admin.ai_configs.orchestration.member_provider')}
            rules={[
              { required: true, message: t('admin.ai_configs.orchestration.member_provider_required') },
            ]}
          >
            <Select options={enumOptions(ORCHESTRATION_PROVIDERS, aiProviderLabel, t)} />
          </Form.Item>
          <Form.Item
            name={[field.name, 'model']}
            label={t('admin.ai_configs.orchestration.member_model')}
            rules={[
              { required: true, message: t('admin.ai_configs.orchestration.member_model_required') },
              { max: 100 },
            ]}
          >
            <Input className="mono" maxLength={100} />
          </Form.Item>
        </div>
        <Button
          type="text"
          danger
          icon={<DeleteOutlined />}
          aria-label={t('admin.ai_configs.orchestration.remove_member')}
          onClick={onRemove}
          className="u-ml-2"
        />
      </div>
      {needsEndpoint && (
        <Form.Item
          name={[field.name, 'endpoint']}
          label={t('admin.ai_configs.orchestration.member_endpoint')}
          rules={[
            provider === 'OPENAI_COMPATIBLE'
              ? { required: true, message: t('admin.ai_configs.orchestration.member_endpoint_required') }
              : {},
            { max: 500 },
          ]}
        >
          <Input className="mono" maxLength={500} />
        </Form.Item>
      )}
      <div style={GRID_TWO}>
        <Form.Item
          name={[field.name, 'api_key']}
          label={t('admin.ai_configs.orchestration.member_api_key')}
          rules={[{ max: 4096 }]}
        >
          <Input.Password className="mono" maxLength={4096} autoComplete="off" />
        </Form.Item>
        <Form.Item
          name={[field.name, 'weight']}
          label={t('admin.ai_configs.orchestration.member_weight')}
          rules={[
            {
              validator: (_, value?: number) =>
                value === undefined || value === null || value > 0
                  ? Promise.resolve()
                  : Promise.reject(new Error(t('admin.ai_configs.orchestration.weight_positive'))),
            },
          ]}
        >
          <InputNumber className="mono u-w-full" min={0} step={0.5} />
        </Form.Item>
      </div>
      <Form.Item
        name={[field.name, 'enabled']}
        label={t('admin.ai_configs.orchestration.member_enabled')}
        valuePropName="checked"
      >
        <Switch />
      </Form.Item>
    </div>
  );
}
