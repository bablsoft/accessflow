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
      <div style={{ width: 440 }}>
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
              {t('auth.invitation.title')}
            </div>
            <div className="muted" style={{ fontSize: 13 }}>
              {t('auth.invitation.subtitle')}
            </div>
          </div>

          {loading ? (
            <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}>
              <Spin />
            </div>
          ) : loadError ? (
            <Alert
              type="error"
              message={loadError.message}
              description={
                loadError.traceId ? <TraceIdFooter traceId={loadError.traceId} /> : undefined
              }
              showIcon
            />
          ) : preview ? (
            <>
              <div className="muted" style={{ fontSize: 13, marginBottom: 16 }}>
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
                  message={submitError.message}
                  description={
                    submitError.traceId ? (
                      <TraceIdFooter traceId={submitError.traceId} />
                    ) : undefined
                  }
                  style={{ marginBottom: 16 }}
                  showIcon
                  closable
                  onClose={() => setSubmitError(null)}
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
        </div>
      </div>
    </div>
  );
}

export default AcceptInvitePage;
