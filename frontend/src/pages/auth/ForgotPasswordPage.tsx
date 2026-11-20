import { AuthLayout } from '@/components/auth/AuthLayout';
import { useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { requestPasswordReset } from '@/api/passwordReset';
import { apiErrorTraceId, resetPasswordErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';

interface ForgotPasswordFormValues {
  email: string;
}

function ForgotPasswordPage() {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);
  const [form] = Form.useForm<ForgotPasswordFormValues>();

  const onFinish = async (values: ForgotPasswordFormValues): Promise<void> => {
    setError(null);
    setSubmitting(true);
    try {
      await requestPasswordReset(values.email.trim());
      setSubmitted(true);
    } catch (err) {
      setError({ message: resetPasswordErrorMessage(err), traceId: apiErrorTraceId(err) });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title={submitted ? t('auth.forgot_password.success_title') : t('auth.forgot_password.title')}
      subtitle={submitted ? t('auth.forgot_password.success_body') : t('auth.forgot_password.subtitle')}
    >
          {!submitted && error && (
            <Alert
              type="error"
              title={error.message}
              description={error.traceId ? <TraceIdFooter traceId={error.traceId} /> : undefined}
              className="u-mb-4"
              showIcon
              closable={{ closeIcon: true, onClose: () => setError(null) }}
            />
          )}

          {!submitted && (
            <Form<ForgotPasswordFormValues>
              form={form}
              layout="vertical"
              onFinish={onFinish}
              requiredMark={false}
              disabled={submitting}
            >
              <Form.Item
                label={t('auth.forgot_password.email_label')}
                name="email"
                rules={[
                  { required: true, message: t('validation.email_required') },
                  { type: 'email', message: t('validation.email_invalid') },
                ]}
              >
                <Input
                  type="email"
                  placeholder={t('auth.forgot_password.email_placeholder')}
                  autoComplete="email"
                />
              </Form.Item>

              <Button
                type="primary"
                size="large"
                block
                htmlType="submit"
                disabled={submitting}
                loading={submitting}
              >
                {submitting
                  ? t('auth.forgot_password.submitting')
                  : t('auth.forgot_password.submit')}
              </Button>
            </Form>
          )}

          <div className="u-mt-4 u-text-center">
            <Link to="/login" className="muted u-text-xs">
              {t('auth.forgot_password.back_to_login')}
            </Link>
          </div>
    </AuthLayout>
  );
}

export default ForgotPasswordPage;
