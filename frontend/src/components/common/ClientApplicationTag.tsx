import { Space, Tag, Tooltip, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ApplicationNameSource } from '@/types/api';

interface ClientApplicationTagProps {
  name?: string | null;
  source?: ApplicationNameSource | string | null;
  testId?: string;
}

/**
 * The calling application recorded on a request (#938). A name from the API key renders plainly;
 * one from the caller-controlled `X-AccessFlow-Application` header carries an "Untrusted" tag.
 * Renders nothing when no application was recorded.
 */
export function ClientApplicationTag({
  name,
  source,
  testId = 'client-application',
}: ClientApplicationTagProps) {
  const { t } = useTranslation();
  const trimmed = name?.trim();
  if (!trimmed) return null;
  const untrusted = source === 'HEADER' || source === 'header';
  return (
    <Space size={4} data-testid={testId}>
      <Tooltip
        title={
          untrusted
            ? t('client_application.untrusted_tooltip')
            : t('client_application.trusted_tooltip')
        }
      >
        <Typography.Text code>{trimmed}</Typography.Text>
      </Tooltip>
      {untrusted && (
        <Tag color="warning" data-testid={`${testId}-untrusted`} style={{ marginInlineEnd: 0 }}>
          {t('client_application.untrusted')}
        </Tag>
      )}
    </Space>
  );
}
