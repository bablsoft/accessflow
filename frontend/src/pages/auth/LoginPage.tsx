import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import {
  ArrowRightOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  SafetyCertificateOutlined,
  LoginOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/store/authStore';
import { apiErrorTraceId, authErrorMessage, isTotpRequiredError } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';
import {
  getSamlEnabled,
  listOAuth2Providers,
  oauth2ProvidersKeys,
  samlEnabledKeys,
} from '@/api/auth';
import { apiBaseUrl } from '@/api/client';
import type { OAuth2Provider } from '@/types/api';

interface LoginLocationState {
  setupSuccess?: boolean;
}

interface LoginFormValues {
  email: string;
  password: string;
}

interface TotpFormValues {
  totp_code: string;
}

type Stage = 'CREDENTIALS' | 'TOTP';

export function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const initialSetupSuccess =
    (location.state as LoginLocationState | null)?.setupSuccess === true;
  const [showPw, setShowPw] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);
  const [setupSuccess, setSetupSuccess] = useState(initialSetupSuccess);
  const [stage, setStage] = useState<Stage>('CREDENTIALS');
  const [pendingCredentials, setPendingCredentials] = useState<LoginFormValues | null>(null);
  const login = useAuthStore((s) => s.login);
  const [form] = Form.useForm<LoginFormValues>();
  const [totpForm] = Form.useForm<TotpFormValues>();

  useEffect(() => {
    if (initialSetupSuccess) {
      // Clear the navigation state so a refresh doesn't re-show the banner.
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [initialSetupSuccess, location.pathname, navigate]);

  const onCredentialsFinish = async (values: LoginFormValues): Promise<void> => {
    setError(null);
    setLoading(true);
    try {
      await login(values.email, values.password);
      navigate('/editor');
    } catch (err) {
      if (isTotpRequiredError(err)) {
        setPendingCredentials(values);
        setStage('TOTP');
        setError(null);
      } else {
        setError({ message: authErrorMessage(err), traceId: apiErrorTraceId(err) });
      }
    } finally {
      setLoading(false);
    }
  };

  const onTotpFinish = async (values: TotpFormValues): Promise<void> => {
    if (!pendingCredentials) return;
    setError(null);
    setLoading(true);
    try {
      await login(pendingCredentials.email, pendingCredentials.password, values.totp_code);
      navigate('/editor');
    } catch (err) {
      setError({ message: authErrorMessage(err), traceId: apiErrorTraceId(err) });
    } finally {
      setLoading(false);
    }
  };

  const backToCredentials = () => {
    setStage('CREDENTIALS');
    setError(null);
    setPendingCredentials(null);
    totpForm.resetFields();
  };

  const samlLogin = () => {
    // SAML SSO is wired in a follow-up; FE-01 covers /auth/* local auth only.
    setError({ message: t('auth.login.saml_not_configured') });
  };

  const oauth2ProvidersQuery = useQuery({
    queryKey: oauth2ProvidersKeys.all,
    queryFn: listOAuth2Providers,
    // Public endpoint — keep it short-lived so newly-enabled providers show up.
    staleTime: 30_000,
  });

  const samlEnabledQuery = useQuery({
    queryKey: samlEnabledKeys.all,
    queryFn: getSamlEnabled,
    staleTime: 30_000,
  });

  const startOAuth2 = (provider: OAuth2Provider) => {
    const base = apiBaseUrl().replace(/\/+$/, '');
    window.location.assign(`${base}/api/v1/auth/oauth2/authorize/${provider.toLowerCase()}`);
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
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            marginBottom: 28,
            justifyContent: 'center',
          }}
        >
          <div
            style={{
              width: 32,
              height: 32,
              borderRadius: 8,
              background: 'var(--fg)',
              color: 'var(--fg-inverse)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontFamily: 'var(--font-mono)',
              fontWeight: 700,
              fontSize: 13,
            }}
          >
            AF
          </div>
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
              {stage === 'TOTP' ? t('totp_login.title') : t('auth.login.title')}
            </div>
            <div className="muted" style={{ fontSize: 13 }}>
              {stage === 'TOTP' ? t('totp_login.subtitle') : t('auth.login.subtitle')}
            </div>
          </div>

          {setupSuccess && (
            <Alert
              type="success"
              message={t('auth.login.setup_success_banner')}
              style={{ marginBottom: 16 }}
              showIcon
              closable
              onClose={() => setSetupSuccess(false)}
            />
          )}

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

          {stage === 'CREDENTIALS' && (() => {
            const oauth2Providers = oauth2ProvidersQuery.data ?? [];
            const samlEnabled = samlEnabledQuery.data === true;
            const hasSso = samlEnabled || oauth2Providers.length > 0;
            return (
              <>
                {samlEnabled && (
                  <Button
                    size="large"
                    block
                    onClick={samlLogin}
                    style={{ marginBottom: 12 }}
                    icon={<SafetyCertificateOutlined />}
                  >
                    {t('auth.login.saml_button')}
                  </Button>
                )}
                {oauth2Providers.map((p) => (
                  <Button
                    key={p.provider}
                    size="large"
                    block
                    onClick={() => startOAuth2(p.provider)}
                    style={{ marginBottom: 12 }}
                    icon={<LoginOutlined />}
                  >
                    {t('auth.login.oauth_button', { provider: p.display_name })}
                  </Button>
                ))}
                {hasSso && (
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 12,
                      margin: '20px 0',
                    }}
                  >
                    <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
                    <span className="muted mono" style={{ fontSize: 10 }}>
                      {t('auth.login.or_divider')}
                    </span>
                    <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
                  </div>
                )}
              </>
            );
          })()}

          {stage === 'CREDENTIALS' && (
            <Form<LoginFormValues>
              form={form}
              layout="vertical"
              onFinish={onCredentialsFinish}
              requiredMark={false}
              disabled={loading}
            >
              <Form.Item
                name="email"
                label={t('auth.login.email_label')}
                rules={[
                  { required: true, message: t('validation.email_required') },
                  { type: 'email', message: t('validation.email_invalid') },
                ]}
              >
                <Input
                  id="login-email"
                  type="email"
                  autoComplete="email"
                  placeholder={t('auth.login.email_placeholder')}
                />
              </Form.Item>

              <Form.Item
                name="password"
                label={t('auth.login.password_label')}
                rules={[
                  { required: true, message: t('validation.password_required') },
                  { min: 8, max: 128, message: t('validation.password_size') },
                ]}
              >
                <Input
                  id="login-password"
                  type={showPw ? 'text' : 'password'}
                  autoComplete="current-password"
                  suffix={
                    <button
                      type="button"
                      onClick={() => setShowPw(!showPw)}
                      aria-label={showPw ? t('auth.login.hide_password') : t('auth.login.show_password')}
                      style={{
                        background: 'transparent',
                        border: 0,
                        color: 'var(--fg-muted)',
                        cursor: 'pointer',
                      }}
                    >
                      {showPw ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                    </button>
                  }
                />
              </Form.Item>

              <div
                style={{
                  display: 'flex',
                  justifyContent: 'flex-end',
                  marginTop: -8,
                  marginBottom: 12,
                }}
              >
                <a
                  href="#"
                  className="muted"
                  style={{ fontSize: 11, textDecoration: 'none' }}
                  onClick={(e) => e.preventDefault()}
                >
                  {t('auth.login.forgot_link')}
                </a>
              </div>

              <Button
                type="primary"
                size="large"
                block
                htmlType="submit"
                disabled={loading}
                icon={loading ? <LoadingOutlined /> : <ArrowRightOutlined />}
              >
                {loading ? t('auth.login.submitting') : t('auth.login.submit')}
              </Button>
            </Form>
          )}

          {stage === 'TOTP' && (
            <Form<TotpFormValues>
              form={totpForm}
              layout="vertical"
              onFinish={onTotpFinish}
              requiredMark={false}
              disabled={loading}
            >
              <Form.Item
                name="totp_code"
                label={t('totp_login.code_label')}
                rules={[
                  { required: true, message: t('validation.totp_code_required') },
                  { pattern: /^\d{6}$/, message: t('validation.totp_code_pattern') },
                ]}
              >
                <Input
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  autoFocus
                  placeholder="123456"
                  style={{ textAlign: 'center', fontFamily: 'monospace', fontSize: 18, letterSpacing: 4 }}
                />
              </Form.Item>
              <Button
                type="primary"
                size="large"
                block
                htmlType="submit"
                disabled={loading}
                icon={loading ? <LoadingOutlined /> : <ArrowRightOutlined />}
              >
                {loading ? t('totp_login.submitting') : t('totp_login.submit')}
              </Button>
              <Button
                type="link"
                block
                onClick={backToCredentials}
                disabled={loading}
                style={{ marginTop: 8 }}
              >
                {t('totp_login.back')}
              </Button>
            </Form>
          )}
        </div>
      </div>
    </div>
  );
}
