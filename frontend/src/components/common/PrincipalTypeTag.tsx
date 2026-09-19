import { RobotOutlined } from '@ant-design/icons';
import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import type { PrincipalType } from '@/types/api';
import { principalTypeLabel } from '@/utils/enumLabels';
import { Pill } from './Pill';

interface PrincipalTypeTagProps {
  principalType?: PrincipalType | null;
  size?: 'sm' | 'md';
}

/**
 * Badges a service account wherever people are listed (#875). Renders nothing for a person, so
 * it can be dropped beside any user cell without changing the default look.
 */
export function PrincipalTypeTag({ principalType, size = 'sm' }: PrincipalTypeTagProps) {
  const { t } = useTranslation();
  if (principalType !== 'SERVICE_ACCOUNT') return null;
  return (
    <Tooltip title={t('common.principal_service_account_tooltip')}>
      <span data-testid="principal-type-tag">
        <Pill
          fg="var(--status-info)"
          bg="var(--status-info-bg)"
          border="var(--status-info-border)"
          size={size}
        >
          <RobotOutlined aria-hidden style={{ marginRight: 4 }} />
          {principalTypeLabel(t, principalType)}
        </Pill>
      </span>
    </Tooltip>
  );
}
