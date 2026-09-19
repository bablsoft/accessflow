import { useEffect } from 'react';
import { App, Button, Form, InputNumber, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ServiceAccount } from '@/types/api';
import {
  UPDATE_FORM_CONSTRAINTS,
  fieldRules,
  limitsUpdateInput,
  type LimitsFormValues,
} from '@/pages/admin/service-accounts/serviceAccountForm';
import { useServiceAccountUpdate } from './useServiceAccountUpdate';

export function ServiceAccountLimitsTab({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [form] = Form.useForm<LimitsFormValues>();
  const saveMutation = useServiceAccountUpdate(account.id, t('admin.service_accounts.limits.success'));

  useEffect(() => {
    form.setFieldsValue({
      rate_limit_per_minute: account.rate_limit_per_minute,
      rate_limit_per_day: account.rate_limit_per_day,
    });
  }, [account, form]);

  return (
    <div style={{ maxWidth: 480 }}>
      <Typography.Paragraph type="secondary">{t('admin.service_accounts.limits.description')}</Typography.Paragraph>
      <Form<LimitsFormValues>
        form={form}
        name="serviceAccountLimits"
        layout="vertical"
        onFinish={(values) => {
          const input = limitsUpdateInput(values, account);
          if (Object.keys(input).length === 0) {
            message.info(t('admin.service_accounts.overview.nothing_changed'));
            return;
          }
          saveMutation.mutate(input);
        }}
      >
        {/* Rules derive from UPDATE_FORM_CONSTRAINTS (createFormParity.test.ts). */}
        <Form.Item
          name="rate_limit_per_minute"
          label={t('admin.service_accounts.limits.label_per_minute')}
          rules={fieldRules(t, UPDATE_FORM_CONSTRAINTS.rate_limit_per_minute)}
        >
          <InputNumber
            min={1}
            precision={0}
            style={{ width: '100%' }}
            placeholder={t('admin.service_accounts.limits.placeholder_default')}
          />
        </Form.Item>
        <Form.Item
          name="rate_limit_per_day"
          label={t('admin.service_accounts.limits.label_per_day')}
          rules={fieldRules(t, UPDATE_FORM_CONSTRAINTS.rate_limit_per_day)}
        >
          <InputNumber
            min={1}
            precision={0}
            style={{ width: '100%' }}
            placeholder={t('admin.service_accounts.limits.placeholder_default')}
          />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
          {t('admin.service_accounts.limits.save')}
        </Button>
      </Form>
    </div>
  );
}
