import { useState } from 'react';
import { Alert, Button, Form, Input, Switch } from 'antd';
import { ArrowRightOutlined, LoadingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { submitSetup, type SetupRequest } from '@/api/setup';
import { updateSystemSmtp } from '@/api/admin';
import { useSetupStore } from '@/store/setupStore';
import { useAuthStore } from '@/store/authStore';
import { apiErrorTraceId, setupErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import { LogoMark } from '@/components/common/LogoMark';
import type { UpdateSystemSmtpInput } from '@/types/api';

interface SetupFormValues {
  organization_name: string;
  email: string;
  display_name?: string;
  password: string;
  confirm_password: string;
}

interface SmtpFormValues {
  host: string;
  port: number;
  username?: string;
  smtp_password?: string;
  tls: boolean;
  from_address: string;
  from_name?: string;
}

type Step = 'account' | 'smtp';

export function SetupPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const setSetupRequired = useSetupStore((s) => s.setSetupRequired);
  const setSession = useAuthStore((s) => s.setSession);
  const [step, setStep] = useState<Step>('account');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);
  const [accountForm] = Form.useForm<SetupFormValues>();
  const [smtpForm] = Form.useForm<SmtpFormValues>();

  const onAccountFinish = async (values: SetupFormValues): Promise<void> => {
    setError(null);
    setSubmitting(true);
    try {
      const req: SetupRequest = {
        organization_name: values.organization_name.trim(),
        email: values.email.trim(),
        password: values.password,
      };
      const displayName = values.display_name?.trim();
      if (displayName) req.display_name = displayName;
      const session = await submitSetup(req);
      setSetupRequired(false);
      setSession(session);
      setStep('smtp');
    } catch (err) {
      setError({ message: setupErrorMessage(err), traceId: apiErrorTraceId(err) });
    } finally {
      setSubmitting(false);
    }
  };

  const onSmtpFinish = async (values: SmtpFormValues): Promise<void> => {
    setError(null);
    setSubmitting(true);
    try {
      const payload: UpdateSystemSmtpInput = {
        host: values.host.trim(),
        port: values.port,
        tls: values.tls,
        from_address: values.from_address.trim(),
      };
      const username = values.username?.trim();
      if (username) payload.username = username;
      const password = values.smtp_password?.trim();
      if (password) payload.smtp_password = password;
      const fromName = values.from_name?.trim();
      if (fromName) payload.from_name = fromName;
      await updateSystemSmtp(payload);
      navigate('/queries');
    } catch (err) {
      setError({ message: setupErrorMessage(err), traceId: apiErrorTraceId(err) });
    } finally {
      setSubmitting(false);
    }
  };

  const onSmtpSkip = (): void => {
    navigate('/queries');
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
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            marginBottom: 28,
            justifyContent: 'center',
          }}
        >
          <span style={{ color: 'var(--fg)', display: 'inline-flex' }}>
            <LogoMark size={32} />
          </span>
          <div>
            <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.01em' }}>
              {t('common.app_name')}
            </div>
            <div className="mono muted" style={{ fontSize: 10 }}>
              v0.1.0
            </div>
          </div>
        </div>

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
              {step === 'account'
                ? t('auth.setup.title')
                : t('auth.setup.smtp.title')}
            </div>
            <div className="muted" style={{ fontSize: 13 }}>
              {step === 'account'
                ? t('auth.setup.subtitle')
                : t('auth.setup.smtp.subtitle')}
            </div>
          </div>

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

          {step === 'account' ? (
            <Form<SetupFormValues>
              form={accountForm}
              layout="vertical"
              onFinish={onAccountFinish}
              requiredMark={false}
              disabled={submitting}
            >
              <Form.Item
                label={t('auth.setup.org_name_label')}
                name="organization_name"
                rules={[
                  { required: true, message: t('validation.org_name_required') },
                  { max: 255, message: t('validation.field_max_255') },
                ]}
              >
                <Input
                  placeholder={t('auth.setup.org_name_placeholder')}
                  autoComplete="organization"
                />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.email_label')}
                name="email"
                rules={[
                  { required: true, message: t('validation.email_required') },
                  { type: 'email', message: t('validation.email_invalid') },
                  { max: 255, message: t('validation.field_max_255') },
                ]}
              >
                <Input
                  type="email"
                  placeholder={t('auth.setup.email_placeholder')}
                  autoComplete="email"
                />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.display_name_label')}
                name="display_name"
                rules={[{ max: 255, message: t('validation.field_max_255') }]}
              >
                <Input
                  placeholder={t('auth.setup.display_name_placeholder')}
                  autoComplete="name"
                />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.password_label')}
                name="password"
                rules={[
                  { required: true, message: t('validation.password_required') },
                  { min: 8, max: 128, message: t('validation.password_size') },
                ]}
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.confirm_password_label')}
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
                icon={submitting ? <LoadingOutlined /> : <ArrowRightOutlined />}
              >
                {submitting ? t('auth.setup.submitting') : t('auth.setup.submit')}
              </Button>
            </Form>
          ) : (
            <Form<SmtpFormValues>
              form={smtpForm}
              layout="vertical"
              onFinish={onSmtpFinish}
              requiredMark={false}
              disabled={submitting}
              initialValues={{ tls: true, port: 587 }}
            >
              <Form.Item
                label={t('auth.setup.smtp.host_label')}
                name="host"
                rules={[
                  { required: true, message: t('validation.system_smtp.host_required') },
                  { max: 255, message: t('validation.field_max_255') },
                ]}
              >
                <Input placeholder="smtp.example.com" />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.smtp.port_label')}
                name="port"
                rules={[
                  { required: true, message: t('validation.system_smtp.port_range') },
                  {
                    type: 'number',
                    min: 1,
                    max: 65535,
                    message: t('validation.system_smtp.port_range'),
                  },
                ]}
              >
                <Input type="number" placeholder="587" />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.smtp.username_label')}
                name="username"
                rules={[{ max: 255, message: t('validation.field_max_255') }]}
              >
                <Input autoComplete="username" />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.smtp.password_label')}
                name="smtp_password"
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.smtp.tls_label')}
                name="tls"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.smtp.from_address_label')}
                name="from_address"
                rules={[
                  {
                    required: true,
                    message: t('validation.system_smtp.from_address_required'),
                  },
                  { type: 'email', message: t('validation.system_smtp.from_address_invalid') },
                  { max: 255, message: t('validation.field_max_255') },
                ]}
              >
                <Input placeholder="no-reply@example.com" type="email" />
              </Form.Item>

              <Form.Item
                label={t('auth.setup.smtp.from_name_label')}
                name="from_name"
                rules={[{ max: 255, message: t('validation.field_max_255') }]}
              >
                <Input placeholder="AccessFlow" />
              </Form.Item>

              <div style={{ display: 'flex', gap: 8 }}>
                <Button block onClick={onSmtpSkip} disabled={submitting}>
                  {t('auth.setup.smtp.skip')}
                </Button>
                <Button
                  type="primary"
                  block
                  htmlType="submit"
                  disabled={submitting}
                  loading={submitting}
                >
                  {t('auth.setup.smtp.save')}
                </Button>
              </div>
            </Form>
          )}
        </div>

        <div
          className="muted mono"
          style={{
            fontSize: 11,
            textAlign: 'center',
            marginTop: 20,
          }}
        >
          {t('auth.setup.footer')}
        </div>
      </div>
    </div>
  );
}
