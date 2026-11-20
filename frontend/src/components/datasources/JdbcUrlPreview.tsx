import { useTranslation } from 'react-i18next';
import { Typography } from 'antd';

interface JdbcUrlPreviewProps {
  template: string;
  host?: string;
  port?: number | null;
  databaseName?: string;
}

export function JdbcUrlPreview({ template, host, port, databaseName }: JdbcUrlPreviewProps) {
  const { t } = useTranslation();
  const url = template
    .replace(/^jdbc:/, '')
    .replace('{host}', host && host.length > 0 ? host : '{host}')
    .replace('{port}', port != null && !Number.isNaN(port) ? String(port) : '{port}')
    .replace(
      '{database_name}',
      databaseName && databaseName.length > 0 ? databaseName : '{database_name}',
    );

  return (
    <div
      style={{
        padding: '8px 12px',
        background: 'var(--bg-sunken)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-sm)',
      }}
    >
      <div
        className="muted mono u-text-2xs u-mb-1"
        style={{ textTransform: 'uppercase', letterSpacing: '0.04em' }}
      >
        {t('datasources.create.connection_url_preview_label')}
      </div>
      <Typography.Paragraph
        copyable
        className="u-mb-0 u-mono u-text-xs" style={{ wordBreak: 'break-all' }}
      >
        {url}
      </Typography.Paragraph>
    </div>
  );
}
