import { useState } from 'react';
import { Button, Input } from 'antd';
import {
  ArrowRightOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  SafetyCertificateOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';

export function LoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('alice.chen@acme.com');
  const [password, setPassword] = useState('demo-password');
  const [showPw, setShowPw] = useState(false);
  const [loading, setLoading] = useState(false);
  const login = useAuthStore((s) => s.login);
  const edition = useAuthStore((s) => s.edition);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    await login(email);
    setLoading(false);
    navigate('/editor');
  };

  const samlLogin = async () => {
    setLoading(true);
    await login(email);
    setLoading(false);
    navigate('/editor');
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
                type={showPw ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                suffix={
                  <button
                    type="button"
                    onClick={() => setShowPw(!showPw)}
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
