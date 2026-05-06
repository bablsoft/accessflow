import { useEffect, useState, type ReactNode } from 'react';
import { LoadingOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/store/authStore';
import * as authApi from '@/api/auth';

interface BootGateProps {
  children: ReactNode;
}

export function BootGate({ children }: BootGateProps) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const payload = await authApi.refresh();
        if (!cancelled) useAuthStore.getState().setSession(payload);
      } catch {
        // No valid refresh cookie — proceed unauthenticated; AuthGuard will redirect.
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (!ready) {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--bg)',
          color: 'var(--fg-muted)',
          fontSize: 24,
        }}
        aria-label="Loading"
      >
        <LoadingOutlined />
      </div>
    );
  }

  return <>{children}</>;
}
