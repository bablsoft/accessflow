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
        className="muted mono"
        style={{
          fontSize: 10,
          textTransform: 'uppercase',
          letterSpacing: '0.04em',
          marginBottom: 4,
        }}
      >
        {t('datasources.create.connection_url_preview_label')}
      </div>
      <Typography.Paragraph
        copyable
        style={{ marginBottom: 0, fontFamily: 'var(--font-mono)', fontSize: 12, wordBreak: 'break-all' }}
      >
        {url}
      </Typography.Paragraph>
    </div>
  );
}
