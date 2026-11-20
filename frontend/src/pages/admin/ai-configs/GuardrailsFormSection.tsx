import { Button, Form, Input } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

/**
 * Guardrail prompt-block patterns rendered inside a parent AntD Form (AF-450). Each entry is a
 * case-insensitive regex matched against the submitted SQL / NL prompt before any model call.
 * Patterns are validated client-side ({@code new RegExp}) to mirror the backend's save-time check.
 */
export function GuardrailsFormSection() {
  const { t } = useTranslation();

  return (
    <div className="u-mt-2 u-border-t u-pt-4">
      <h3 className="u-mb-1">{t('admin.ai_configs.guardrails.section_title')}</h3>
      <p className="muted u-mt-0">
        {t('admin.ai_configs.guardrails.section_help')}
      </p>
      <Form.List name="guardrail_patterns">
        {(fields, { add, remove }) => (
          <>
            {fields.map((field) => (
              <div
                key={field.key}
                className="u-flex u-gap-2 u-items-start"
                data-testid="guardrail-pattern"
              >
                <Form.Item
                  name={field.name}
                  className="u-flex-1"
                  rules={[
                    { max: 500 },
                    {
                      validator: (_, value?: string) => {
                        if (!value) {
                          return Promise.resolve();
                        }
                        try {
                          // eslint-disable-next-line no-new
                          new RegExp(value);
                          return Promise.resolve();
                        } catch {
                          return Promise.reject(
                            new Error(t('admin.ai_configs.guardrails.invalid_regex')),
                          );
                        }
                      },
                    },
                  ]}
                >
                  <Input
                    className="mono"
                    maxLength={500}
                    placeholder={t('admin.ai_configs.guardrails.pattern_placeholder')}
                  />
                </Form.Item>
                <Button
                  type="text"
                  danger
                  icon={<DeleteOutlined />}
                  aria-label={t('admin.ai_configs.guardrails.remove_pattern')}
                  onClick={() => remove(field.name)}
                />
              </div>
            ))}
            <Button type="dashed" block icon={<PlusOutlined />} onClick={() => add('')}>
              {t('admin.ai_configs.guardrails.add_pattern')}
            </Button>
          </>
        )}
      </Form.List>
    </div>
  );
}
