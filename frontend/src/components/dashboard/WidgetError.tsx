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
    <div role="alert" className="u-text-center u-py-5 u-px-4">
      <div
        className="u-flex u-items-center u-justify-center u-fg-warning" style={{ width: 36, height: 36, margin: '0 auto 10px', borderRadius: 10, background: 'var(--status-warn-bg)', border: '1px solid var(--status-warn-border)' }}
      >
        <WarningOutlined className="u-text-lg" />
      </div>
      <div className="u-fw-600 u-mb-1 u-text-sm">
        {t('dashboard.error_title')}
      </div>
      <div className="muted u-text-xs u-max-w-xs u-mx-auto">
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
