import { useState } from 'react';
import { Alert, App, Button, Form, Input } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { changePassword } from '@/api/me';
import { apiErrorTraceId, profileErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import { useAuthStore } from '@/store/authStore';

interface ChangePasswordFormValues {
  current_password: string;
  new_password: string;
  confirm_password: string;
}

export function ChangePasswordForm() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const clearAuth = useAuthStore((s) => s.clear);
  const [form] = Form.useForm<ChangePasswordFormValues>();
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);

  const mutation = useMutation({
    mutationFn: (values: ChangePasswordFormValues) =>
      changePassword({
        current_password: values.current_password,
        new_password: values.new_password,
      }),
    onSuccess: () => {
      setError(null);
      message.success(t('profile.password.saved'));
      form.resetFields();
      // Sessions were revoked server-side — force re-login.
      clearAuth();
      navigate('/login', { replace: true });
    },
    onError: (err) => setError({ message: profileErrorMessage(err), traceId: apiErrorTraceId(err) }),
  });

  return (
    <Form<ChangePasswordFormValues>
      form={form}
      layout="vertical"
      onFinish={(values) => mutation.mutate(values)}
      disabled={mutation.isPending}
    >
      {error && (
        <Alert
          type="error"
          message={error.message}
          description={error.traceId ? <TraceIdFooter traceId={error.traceId} /> : undefined}
          style={{ marginBottom: 16 }}
          showIcon
          closable
          onClose={() => setError(null)}
        />
      )}
      <Form.Item
        name="current_password"
        label={t('profile.password.current_label')}
        rules={[{ required: true, message: t('validation.current_password_required') }]}
      >
        <Input.Password autoComplete="current-password" />
      </Form.Item>
      <Form.Item
        name="new_password"
        label={t('profile.password.new_label')}
        rules={[
          { required: true, message: t('validation.new_password_required') },
          { min: 8, max: 128, message: t('validation.new_password_size') },
        ]}
      >
        <Input.Password autoComplete="new-password" />
      </Form.Item>
      <Form.Item
        name="confirm_password"
        label={t('profile.password.confirm_label')}
        dependencies={['new_password']}
        rules={[
          { required: true, message: t('validation.confirm_password_required') },
          ({ getFieldValue }) => ({
            validator(_, value) {
              if (!value || getFieldValue('new_password') === value) {
                return Promise.resolve();
              }
              return Promise.reject(new Error(t('validation.passwords_mismatch')));
            },
          }),
        ]}
      >
        <Input.Password autoComplete="new-password" />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={mutation.isPending}>
        {t('profile.password.save')}
      </Button>
    </Form>
  );
}
