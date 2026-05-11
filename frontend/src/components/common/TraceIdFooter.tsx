import { CopyOutlined } from '@ant-design/icons';
import { App, Button } from 'antd';
import { useTranslation } from 'react-i18next';

interface TraceIdFooterProps {
  traceId: string;
}

export function TraceIdFooter({ traceId }: TraceIdFooterProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(traceId);
      message.success(t('errors.trace_id_copied'));
    } catch {
      // Clipboard may be unavailable in non-secure contexts; the id is still on screen.
    }
  };

  const truncated = traceId.length > 12 ? `${traceId.slice(0, 8)}…${traceId.slice(-4)}` : traceId;

  return (
    <span className="af-trace-id" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <span style={{ color: 'var(--fg-muted)', fontSize: 12 }}>
        {t('errors.trace_id_label')}:
      </span>
      <code
        title={traceId}
        style={{ fontSize: 12, color: 'var(--fg-muted)' }}
      >
        {truncated}
      </code>
      <Button
        type="text"
        size="small"
        aria-label={t('errors.trace_id_copy')}
        icon={<CopyOutlined />}
        onClick={onCopy}
      />
    </span>
  );
}
