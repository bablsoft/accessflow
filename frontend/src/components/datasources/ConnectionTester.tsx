import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Spin } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ConnectionTestResult, DriverStatus } from '@/types/api';

const RESOLVING_DRIVER_DELAY_MS = 800;

interface ConnectionTesterProps {
  driverStatus: DriverStatus;
  pending: boolean;
  result: ConnectionTestResult | null;
  errorMessage: string | null;
  onRunTest: () => void;
}

export function ConnectionTester({
  driverStatus,
  pending,
  result,
  errorMessage,
  onRunTest,
}: ConnectionTesterProps) {
  const { t } = useTranslation();
  const [showResolving, setShowResolving] = useState(false);
  const timeoutRef = useRef<number | null>(null);

  useEffect(() => {
    if (pending && driverStatus === 'AVAILABLE') {
      timeoutRef.current = window.setTimeout(() => setShowResolving(true), RESOLVING_DRIVER_DELAY_MS);
    } else {
      setShowResolving(false);
      if (timeoutRef.current != null) {
        window.clearTimeout(timeoutRef.current);
        timeoutRef.current = null;
      }
    }
    return () => {
      if (timeoutRef.current != null) {
        window.clearTimeout(timeoutRef.current);
        timeoutRef.current = null;
      }
    };
  }, [pending, driverStatus]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Button
        type="primary"
        icon={<ThunderboltOutlined />}
        onClick={onRunTest}
        loading={pending}
        disabled={pending}
      >
        {showResolving
          ? t('datasources.create.test_resolving_driver')
          : t('datasources.create.test_button')}
      </Button>
      {pending && showResolving && (
        <div className="muted" style={{ fontSize: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
          <Spin size="small" />
          {t('datasources.create.test_resolving_driver_hint')}
        </div>
      )}
      {!pending && result?.ok && (
        <Alert
          type="success"
          showIcon
          icon={<CheckCircleOutlined />}
          title={t('datasources.create.test_success', { ms: result.latency_ms })}
        />
      )}
      {!pending && (errorMessage != null || (result != null && !result.ok)) && (
        <Alert
          type="error"
          showIcon
          icon={<CloseCircleOutlined />}
          title={t('datasources.create.test_failure')}
          description={errorMessage ?? result?.message ?? null}
        />
      )}
    </div>
  );
}
