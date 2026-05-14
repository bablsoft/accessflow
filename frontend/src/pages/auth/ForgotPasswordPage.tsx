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
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--bg)',
        padding: 24,
      }}
    >
      <div style={{ width: 380 }}>
        <div
          className="af-card"
          style={{
            padding: 24,
            background: 'var(--bg-elev)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
          }}
        >
          <div style={{ marginBottom: 20 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>
              {submitted
                ? t('auth.forgot_password.success_title')
                : t('auth.forgot_password.title')}
            </div>
            <div className="muted" style={{ fontSize: 13 }}>
              {submitted
                ? t('auth.forgot_password.success_body')
                : t('auth.forgot_password.subtitle')}
            </div>
          </div>

          {!submitted && error && (
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

          <div style={{ marginTop: 16, textAlign: 'center' }}>
            <Link to="/login" className="muted" style={{ fontSize: 12 }}>
              {t('auth.forgot_password.back_to_login')}
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}

export default ForgotPasswordPage;
