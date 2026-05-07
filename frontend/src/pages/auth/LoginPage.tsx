import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import {
  ArrowRightOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  SafetyCertificateOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { authErrorMessage } from '@/utils/apiErrors';

interface LoginLocationState {
  setupSuccess?: boolean;
}

interface LoginFormValues {
  email: string;
  password: string;
}

export function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const initialSetupSuccess =
    (location.state as LoginLocationState | null)?.setupSuccess === true;
  const [showPw, setShowPw] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [setupSuccess, setSetupSuccess] = useState(initialSetupSuccess);
  const login = useAuthStore((s) => s.login);
  const edition = usePreferencesStore((s) => s.edition);
  const [form] = Form.useForm<LoginFormValues>();

  useEffect(() => {
    if (initialSetupSuccess) {
      // Clear the navigation state so a refresh doesn't re-show the banner.
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [initialSetupSuccess, location.pathname, navigate]);

  const onFinish = async (values: LoginFormValues): Promise<void> => {
    setError(null);
    setLoading(true);
    try {
      await login(values.email, values.password);
      navigate('/editor');
    } catch (err) {
      setError(authErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const samlLogin = () => {
    // SAML SSO is wired in a follow-up; FE-01 covers /auth/* local auth only.
    setError(t('auth.login.saml_not_configured'));
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
              {edition.toLowerCase()} · v0.1.0
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
              {t('auth.login.title')}
            </div>
            <div className="muted" style={{ fontSize: 13 }}>
              {t('auth.login.subtitle')}
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
              message={error}
              style={{ marginBottom: 16 }}
              showIcon
              closable
              onClose={() => setError(null)}
            />
          )}

          {edition === 'ENTERPRISE' && (
            <>
              <Button
                size="large"
                block
                onClick={samlLogin}
                style={{ marginBottom: 16 }}
                icon={<SafetyCertificateOutlined />}
              >
                {t('auth.login.saml_button')}
              </Button>
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
            </>
          )}

          <Form<LoginFormValues>
            form={form}
            layout="vertical"
            onFinish={onFinish}
            requiredMark={false}
            disabled={loading}
            initialValues={{
              email: import.meta.env.DEV ? 'alice.chen@acme.com' : '',
              password: import.meta.env.DEV ? 'demo-password' : '',
            }}
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
              label={
                <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
                  <span>{t('auth.login.password_label')}</span>
                  <a
                    href="#"
                    className="muted"
                    style={{ fontSize: 11, textDecoration: 'none' }}
                    onClick={(e) => e.preventDefault()}
                  >
                    Forgot?
                  </a>
                </div>
              }
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
        </div>

        <div
          className="muted mono"
          style={{
            fontSize: 11,
            textAlign: 'center',
            marginTop: 20,
          }}
        >
          deployment · acme.accessflow.internal · region eu-west-1
        </div>
      </div>
    </div>
  );
}
