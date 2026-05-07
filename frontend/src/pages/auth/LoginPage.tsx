import { useEffect, useState } from 'react';
import { Alert, Button, Input } from 'antd';
import {
  ArrowRightOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  SafetyCertificateOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { authErrorMessage } from '@/utils/apiErrors';

interface LoginLocationState {
  setupSuccess?: boolean;
}

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const initialSetupSuccess =
    (location.state as LoginLocationState | null)?.setupSuccess === true;
  const [email, setEmail] = useState(import.meta.env.DEV ? 'alice.chen@acme.com' : '');
  const [password, setPassword] = useState(import.meta.env.DEV ? 'demo-password' : '');
  const [showPw, setShowPw] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [setupSuccess, setSetupSuccess] = useState(initialSetupSuccess);
  const login = useAuthStore((s) => s.login);
  const edition = usePreferencesStore((s) => s.edition);

  useEffect(() => {
    if (initialSetupSuccess) {
      // Clear the navigation state so a refresh doesn't re-show the banner.
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [initialSetupSuccess, location.pathname, navigate]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await login(email, password);
      navigate('/editor');
    } catch (err) {
      setError(authErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const samlLogin = () => {
    // SAML SSO is wired in a follow-up; FE-01 covers /auth/* local auth only.
    setError('SAML SSO is not configured yet. Please sign in with email and password.');
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
              AccessFlow
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
              Sign in to your workspace
            </div>
            <div className="muted" style={{ fontSize: 13 }}>
              Enter your credentials to continue.
            </div>
          </div>

          {setupSuccess && (
            <Alert
              type="success"
              message="Admin created. Sign in to continue."
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
                Continue with SAML SSO
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
                  OR
                </span>
                <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
              </div>
            </>
          )}

          <form onSubmit={submit}>
            <div style={{ marginBottom: 14 }}>
              <label
                className="muted"
                htmlFor="login-email"
                style={{
                  display: 'block',
                  fontSize: 11.5,
                  fontWeight: 500,
                  marginBottom: 5,
                }}
              >
                Email
              </label>
              <Input
                id="login-email"
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@company.com"
              />
            </div>
            <div style={{ marginBottom: 18 }}>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: 5,
                }}
              >
                <label
                  className="muted"
                  htmlFor="login-password"
                  style={{ fontSize: 11.5, fontWeight: 500, margin: 0 }}
                >
                  Password
                </label>
                <a
                  href="#"
                  className="muted"
                  style={{ fontSize: 11, textDecoration: 'none' }}
                  onClick={(e) => e.preventDefault()}
                >
                  Forgot?
                </a>
              </div>
              <Input
                id="login-password"
                type={showPw ? 'text' : 'password'}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                suffix={
                  <button
                    type="button"
                    onClick={() => setShowPw(!showPw)}
                    aria-label={showPw ? 'Hide password' : 'Show password'}
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
            </div>
            <Button
              type="primary"
              size="large"
              block
              htmlType="submit"
              disabled={loading}
              icon={loading ? <LoadingOutlined /> : <ArrowRightOutlined />}
            >
              {loading ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
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
