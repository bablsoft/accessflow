import { AuthLayout } from '@/components/auth/AuthLayout';
import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input, Spin } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getPasswordResetPreview, resetPassword } from '@/api/passwordReset';
import { apiErrorTraceId, resetPasswordErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import type { PasswordResetPreview } from '@/types/api';

interface ResetFormValues {
  password: string;
  confirm_password: string;
}

function ResetPasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { token } = useParams<{ token: string }>();
  const [preview, setPreview] = useState<PasswordResetPreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<{ message: string; traceId?: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<{ message: string; traceId?: string } | null>(
    null,
  );
  const [form] = Form.useForm<ResetFormValues>();

  useEffect(() => {
    if (!token) {
      setLoadError({ message: t('auth.password_reset.error_invalid_token') });
      setLoading(false);
      return;
    }
    let cancelled = false;
    getPasswordResetPreview(token)
      .then((p) => {
        if (!cancelled) {
          setPreview(p);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setLoadError({
            message: resetPasswordErrorMessage(err),
            traceId: apiErrorTraceId(err),
          });
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [token, t]);

  const onFinish = async (values: ResetFormValues): Promise<void> => {
    if (!token) return;
    setSubmitError(null);
    setSubmitting(true);
    try {
      await resetPassword(token, { password: values.password });
      navigate('/login', { state: { passwordReset: true } });
    } catch (err) {
      setSubmitError({
        message: resetPasswordErrorMessage(err),
        traceId: apiErrorTraceId(err),
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title={t('auth.password_reset.title')}
      subtitle={preview ? t('auth.password_reset.subtitle', { email: preview.email }) : undefined}
      wide
    >
          {loading ? (
            <div className="u-flex u-justify-center u-p-8">
              <Spin />
            </div>
          ) : loadError ? (
            <Alert
              type="error"
              title={loadError.message}
              description={
                loadError.traceId ? <TraceIdFooter traceId={loadError.traceId} /> : undefined
              }
              showIcon
            />
          ) : preview ? (
            <>
              {submitError && (
                <Alert
                  type="error"
                  title={submitError.message}
                  description={
                    submitError.traceId ? (
                      <TraceIdFooter traceId={submitError.traceId} />
                    ) : undefined
                  }
                  className="u-mb-4"
                  showIcon
                  closable={{ closeIcon: true, onClose: () => setSubmitError(null) }}
                />
              )}

              <Form<ResetFormValues>
                form={form}
                layout="vertical"
                onFinish={onFinish}
                requiredMark={false}
                disabled={submitting}
              >
                <Form.Item
                  label={t('auth.password_reset.password_label')}
                  name="password"
                  rules={[
                    { required: true, message: t('validation.new_password_required') },
                    { min: 8, max: 128, message: t('validation.new_password_size') },
                  ]}
                >
                  <Input.Password autoComplete="new-password" />
                </Form.Item>

                <Form.Item
                  label={t('auth.password_reset.confirm_password_label')}
                  name="confirm_password"
                  dependencies={['password']}
                  rules={[
                    { required: true, message: t('validation.confirm_password_required') },
                    ({ getFieldValue }) => ({
                      validator(_, value) {
                        if (!value || getFieldValue('password') === value) {
                          return Promise.resolve();
                        }
                        return Promise.reject(new Error(t('validation.passwords_mismatch')));
                      },
                    }),
                  ]}
                >
                  <Input.Password autoComplete="new-password" />
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
                    ? t('auth.password_reset.submitting')
                    : t('auth.password_reset.submit')}
                </Button>
              </Form>
            </>
          ) : null}
    </AuthLayout>
  );
}

export default ResetPasswordPage;
