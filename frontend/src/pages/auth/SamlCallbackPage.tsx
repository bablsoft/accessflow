import { useEffect, useRef, useState } from 'react';
import { Alert, Button } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { exchangeSamlCode } from '@/api/auth';
import { useAuthStore } from '@/store/authStore';
import { homePathForUser } from '@/utils/homePath';
import { apiErrorTraceId, authErrorMessage } from '@/utils/apiErrors';
import { TraceIdFooter } from '@/components/common/TraceIdFooter';

const KNOWN_ERROR_CODES = new Set([
  'SAML_LOGIN_FAILED',
  'SAML_EMAIL_MISSING',
  'SAML_LOCAL_EMAIL_CONFLICT',
  'SAML_SIGNATURE_INVALID',
  'SAML_ASSERTION_INVALID',
  'SAML_NOT_CONFIGURED',
  'SAML_UNEXPECTED_AUTH',
  'ACCOUNT_DISABLED',
  'SERVICE_ACCOUNT_SIGN_IN_BLOCKED',
]);

export function SamlCallbackPage() {
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
        ? `auth.saml_callback.error.${errorParam.toLowerCase()}`
        : 'auth.saml_callback.error.generic';
      setError({ message: t(key) });
      return;
    }
    if (!code || exchangedRef.current) {
      return;
    }
    exchangedRef.current = true;
    void exchangeSamlCode(code)
      .then((payload) => {
        setSession(payload);
        navigate(homePathForUser(payload.user), { replace: true });
      })
      .catch((err) => {
        setError({ message: authErrorMessage(err), traceId: apiErrorTraceId(err) });
      });
  }, [code, errorParam, navigate, setSession, t]);

  return (
    <div
      className="u-flex u-items-center u-justify-center u-p-6" style={{ minHeight: '100vh', background: 'var(--bg)' }}
    >
      <div style={{ width: 380 }}>
        <div
          className="af-card u-p-6"
          style={{ background: 'var(--bg-elev)', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)' }}
        >
          {error ? (
            <>
              <Alert
                type="error"
                showIcon
                title={t('auth.saml_callback.error_title')}
                description={
                  <>
                    {error.message}
                    {error.traceId && <TraceIdFooter traceId={error.traceId} />}
                  </>
                }
                className="u-mb-4"
              />
              <Button block size="large" type="primary" onClick={() => navigate('/login')}>
                {t('auth.saml_callback.back_to_login')}
              </Button>
            </>
          ) : (
            <div
              className="u-flex u-items-center u-gap-3 u-justify-center"
            >
              <LoadingOutlined className="u-text-xl" />
              <span>{t('auth.saml_callback.exchanging')}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default SamlCallbackPage;
