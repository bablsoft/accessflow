import { Tag, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { userDisplay } from '@/utils/userDisplay';

interface OnBehalfOfTagProps {
  email?: string | null;
  displayName?: string | null;
  /** Shown when no name is known — the raw id an audit row carries (#874). */
  userId?: string | null;
  testId?: string;
}

/**
 * "on behalf of <person>" beside an actor (#874, #875) — the same blue-tag treatment the review
 * queue uses for a delegated decision (#622). Renders nothing when there is no attribution.
 */
export function OnBehalfOfTag({
  email,
  displayName,
  userId,
  testId = 'on-behalf-of-tag',
}: OnBehalfOfTagProps) {
  const { t } = useTranslation();
  const name = userDisplay(displayName, email) || userId?.trim() || '';
  if (!name) return null;
  return (
    <Tooltip title={t('common.on_behalf_of_tooltip')}>
      <Tag color="blue" data-testid={testId} style={{ marginInlineEnd: 0 }}>
        {t('common.on_behalf_of', { name })}
      </Tag>
    </Tooltip>
  );
}
