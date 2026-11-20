import { AuthLayout } from '@/components/auth/AuthLayout';
import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input, Spin } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { acceptInvitation, getInvitationPreview } from '@/api/invitations';
import { apiErrorTraceId, setupErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import type { InvitationPreview } from '@/types/api';

interface AcceptFormValues {
  password: string;
  confirm_password: string;
  display_name?: string;
}

function AcceptInvitePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { token } = useParams<{ token: string }>();
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<{ message: string; traceId?: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<{ message: string; traceId?: string } | null>(
    null,
  );
  const [form] = Form.useForm<AcceptFormValues>();

  useEffect(() => {
    if (!token) {
      setLoadError({ message: t('auth.invitation.error_invalid_token') });
      setLoading(false);
      return;
    }
    let cancelled = false;
    getInvitationPreview(token)
      .then((p) => {
        if (!cancelled) {
          setPreview(p);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setLoadError({
            message: setupErrorMessage(err),
            traceId: apiErrorTraceId(err),
          });
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [token, t]);

  const onFinish = async (values: AcceptFormValues): Promise<void> => {
    if (!token) return;
    setSubmitError(null);
    setSubmitting(true);
    try {
      const displayName = values.display_name?.trim();
      await acceptInvitation(token, {
        password: values.password,
        display_name: displayName || null,
      });
      navigate('/login', { state: { invitationAccepted: true } });
    } catch (err) {
      setSubmitError({ message: setupErrorMessage(err), traceId: apiErrorTraceId(err) });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title={t('auth.invitation.title')}
      subtitle={t('auth.invitation.subtitle')}
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
              <div className="muted u-text-sm u-mb-4">
                {t('auth.invitation.greeting', {
                  organization: preview.organization_name,
                  role: preview.role,
                })}
                <br />
                <strong>{preview.email}</strong>
              </div>

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

              <Form<AcceptFormValues>
                form={form}
                layout="vertical"
                onFinish={onFinish}
                requiredMark={false}
                disabled={submitting}
                initialValues={{ display_name: preview.display_name ?? undefined }}
              >
                <Form.Item
                  label={t('auth.invitation.display_name_label')}
                  name="display_name"
                  rules={[{ max: 255, message: t('validation.field_max_255') }]}
                >
                  <Input
                    placeholder={t('auth.invitation.display_name_placeholder')}
                    autoComplete="name"
                  />
                </Form.Item>

                <Form.Item
                  label={t('auth.invitation.password_label')}
                  name="password"
                  rules={[
                    { required: true, message: t('validation.password_required') },
                    { min: 8, max: 128, message: t('validation.password_size') },
                  ]}
                >
                  <Input.Password autoComplete="new-password" />
                </Form.Item>

                <Form.Item
                  label={t('auth.invitation.confirm_password_label')}
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
                  {t('auth.invitation.submit')}
                </Button>
              </Form>
            </>
          ) : null}
    </AuthLayout>
  );
}

export default AcceptInvitePage;
