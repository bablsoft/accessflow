import { useEffect } from 'react';
import { App, Button, Form, Input, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ServiceAccount } from '@/types/api';
import {
  ATTRIBUTE_CONSTRAINTS,
  attributesFormFromAccount,
  attributesUpdateInput,
  duplicateAttributeKeys,
  fieldRules,
  type AttributesFormValues,
} from '@/pages/admin/service-accounts/serviceAccountForm';
import { useServiceAccountUpdate } from './useServiceAccountUpdate';

/**
 * Row-security attributes (#1130) — the only write path for a service account's `:user.<key>`
 * values since `PUT /admin/users/{id}` refuses service accounts. UI-owned, so editable on a
 * `BOOTSTRAP` account too.
 */
export function ServiceAccountAttributesTab({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<AttributesFormValues>();
  const saveMutation = useServiceAccountUpdate(account.id, t('admin.service_accounts.attributes.success'));

  useEffect(() => {
    form.setFieldsValue(attributesFormFromAccount(account));
  }, [account, form]);

  return (
    <div style={{ maxWidth: 640 }}>
      <Typography.Paragraph type="secondary">
        {t('admin.service_accounts.attributes.description')}
      </Typography.Paragraph>
      <Form<AttributesFormValues>
        form={form}
        name="serviceAccountAttributes"
        layout="vertical"
        onFinish={(values) => {
          const input = attributesUpdateInput(values, account);
          if (Object.keys(input).length === 0) {
            message.info(t('admin.service_accounts.overview.nothing_changed'));
            return;
          }
          saveMutation.mutate(input);
        }}
      >
        {/* Bounds mirror UpdateServiceAccountRequest.attributes (createFormParity.test.ts). */}
        <Form.List name="attributes">
          {(fields, { add, remove }) => (
            <>
              {fields.length === 0 && (
                <Typography.Paragraph type="secondary" data-testid="attributes-empty">
                  {t('admin.service_accounts.attributes.empty')}
                </Typography.Paragraph>
              )}
              {fields.map((field) => (
                <div key={field.key} style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                  <Form.Item
                    name={[field.name, 'key']}
                    style={{ flex: 1 }}
                    rules={[
                      ...fieldRules(t, { required: true, max: ATTRIBUTE_CONSTRAINTS.key_max }),
                      ({ getFieldValue }) => ({
                        validator(_, value?: string) {
                          const key = value?.trim();
                          if (key && duplicateAttributeKeys(getFieldValue('attributes')).has(key)) {
                            return Promise.reject(
                              new Error(t('admin.service_accounts.attributes.duplicate_key')),
                            );
                          }
                          return Promise.resolve();
                        },
                      }),
                    ]}
                  >
                    <Input
                      placeholder={t('admin.service_accounts.attributes.key')}
                      aria-label={t('admin.service_accounts.attributes.key')}
                    />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'value']}
                    style={{ flex: 1 }}
                    rules={fieldRules(t, { max: ATTRIBUTE_CONSTRAINTS.value_max })}
                  >
                    <Input
                      placeholder={t('admin.service_accounts.attributes.value')}
                      aria-label={t('admin.service_accounts.attributes.value')}
                    />
                  </Form.Item>
                  <Button
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('admin.service_accounts.attributes.remove')}
                    onClick={() => remove(field.name)}
                  />
                </div>
              ))}
              <Form.Item>
                <Button
                  type="dashed"
                  icon={<PlusOutlined />}
                  onClick={() => add({ key: '', value: '' })}
                  disabled={fields.length >= ATTRIBUTE_CONSTRAINTS.max_entries}
                  block
                >
                  {t('admin.service_accounts.attributes.add')}
                </Button>
              </Form.Item>
            </>
          )}
        </Form.List>
        <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
          {t('admin.service_accounts.attributes.save')}
        </Button>
      </Form>
    </div>
  );
}
