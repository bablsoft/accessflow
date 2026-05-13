import { useEffect, useRef, useState } from 'react';
import { Alert, Button } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { exchangeOAuth2Code } from '@/api/auth';
import { useAuthStore } from '@/store/authStore';
import { apiErrorTraceId, authErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';

const KNOWN_ERROR_CODES = new Set([
  'OAUTH2_LOGIN_FAILED',
  'OAUTH2_EMAIL_MISSING',
  'OAUTH2_EMAIL_UNVERIFIED',
  'OAUTH2_LOCAL_EMAIL_CONFLICT',
  'OAUTH2_UNEXPECTED_AUTH',
  'OAUTH2_UNKNOWN_PROVIDER',
  'ACCOUNT_DISABLED',
]);

export function OAuthCallbackPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const setSession = useAuthStore((s) => s.setSession);
  const [error, setError] = useState<{ message: string; traceId?: string } | null>(null);
  const exchangedRef = useRef(false);

  const code = params.get('code');
  const errorParam = params.get('error');

  useEffect(() => {
    if (errorParam) {
      const key = KNOWN_ERROR_CODES.has(errorParam)
        ? `auth.oauth_callback.error.${errorParam.toLowerCase()}`
        : 'auth.oauth_callback.error.generic';
      setError({ message: t(key) });
      return;
    }
    if (!code || exchangedRef.current) {
      return;
    }
    exchangedRef.current = true;
    void exchangeOAuth2Code(code)
      .then((payload) => {
        setSession(payload);
        navigate('/editor', { replace: true });
      })
      .catch((err) => {
        setError({ message: authErrorMessage(err), traceId: apiErrorTraceId(err) });
      });
  }, [code, errorParam, navigate, setSession, t]);

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
          {error ? (
            <>
              <Alert
                type="error"
                showIcon
                message={t('auth.oauth_callback.error_title')}
                description={
                  <>
                    {error.message}
                    {error.traceId && <TraceIdFooter traceId={error.traceId} />}
                  </>
                }
                style={{ marginBottom: 16 }}
              />
              <Button block size="large" type="primary" onClick={() => navigate('/login')}>
                {t('auth.oauth_callback.back_to_login')}
              </Button>
            </>
          ) : (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                justifyContent: 'center',
              }}
            >
              <LoadingOutlined style={{ fontSize: 20 }} />
              <span>{t('auth.oauth_callback.exchanging')}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default OAuthCallbackPage;
