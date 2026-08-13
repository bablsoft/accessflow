import { Button } from 'antd';
import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { dashboardErrorMessage } from '@/utils/apiErrors';

interface WidgetErrorProps {
  error: unknown;
  onRetry?: () => void;
  /** Domain error-message builder; defaults to the dashboard handler. */
  messageFor?: (err: unknown) => string;
}

/**
 * Compact in-widget error block (AF-498 redesign): surfaces the server's localized `detail` via
 * the domain handler instead of silently falling through to an empty state.
 */
export function WidgetError({ error, onRetry, messageFor = dashboardErrorMessage }: WidgetErrorProps) {
  const { t } = useTranslation();
  return (
    <div role="alert" style={{ padding: '20px 16px', textAlign: 'center' }}>
      <div
        style={{
          width: 36,
          height: 36,
          margin: '0 auto 10px',
          borderRadius: 10,
          background: 'var(--status-warn-bg)',
          border: '1px solid var(--status-warn-border)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--status-warn)',
        }}
      >
        <WarningOutlined style={{ fontSize: 16 }} />
      </div>
      <div style={{ fontWeight: 600, marginBottom: 4, fontSize: 13 }}>
        {t('dashboard.error_title')}
      </div>
      <div className="muted" style={{ fontSize: 12, maxWidth: 360, margin: '0 auto' }}>
        {messageFor(error)}
      </div>
      {onRetry && (
        <Button size="small" icon={<ReloadOutlined />} onClick={onRetry} style={{ marginTop: 10 }}>
          {t('dashboard.retry')}
        </Button>
      )}
    </div>
  );
}
