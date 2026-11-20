import { useEffect, useState, type ReactNode } from 'react';
import { LoadingOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '@/store/authStore';
import { useSetupStore } from '@/store/setupStore';
import * as authApi from '@/api/auth';
import * as setupApi from '@/api/setup';

interface BootGateProps {
  children: ReactNode;
}

export function BootGate({ children }: BootGateProps) {
  const { t } = useTranslation();
  const [ready, setReady] = useState(false);
  const [bootError, setBootError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const status = await setupApi.getSetupStatus();
        if (cancelled) return;
        useSetupStore.getState().setSetupRequired(status.setup_required);
        if (!status.setup_required) {
          try {
            const payload = await authApi.refresh();
            if (!cancelled) useAuthStore.getState().setSession(payload);
          } catch {
            // No valid refresh cookie — proceed unauthenticated; AuthGuard will redirect.
          }
        }
      } catch {
        if (!cancelled) {
          setBootError(t('errors.server_unreachable'));
        }
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [t]);

  if (!ready) {
    return (
      <div
        className="u-flex u-items-center u-justify-center u-fg-muted u-text-2xl" style={{ minHeight: '100vh', background: 'var(--bg)' }}
        aria-label="Loading"
      >
        <LoadingOutlined />
      </div>
    );
  }

  if (bootError) {
    return (
      <div
        role="alert"
        className="u-flex u-col u-items-center u-justify-center u-gap-3 u-fg u-p-6 u-text-center" style={{ minHeight: '100vh', background: 'var(--bg)' }}
      >
        <div className="u-text-lg u-fw-600">{bootError}</div>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="u-fg" style={{ padding: '8px 16px', border: '1px solid var(--border)', borderRadius: 6, background: 'var(--bg-elev)', cursor: 'pointer' }}
        >
          {t('common.retry')}
        </button>
      </div>
    );
  }

  return <>{children}</>;
}
