import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import type { DriverStatus } from '@/types/api';

interface DriverStatusBadgeProps {
  status: DriverStatus;
  size?: 'sm' | 'md';
}

export function DriverStatusBadge({ status, size = 'md' }: DriverStatusBadgeProps) {
  const { t } = useTranslation();
  switch (status) {
    case 'READY':
      return (
        <Pill
          fg="var(--risk-low)"
          bg="var(--risk-low-bg)"
          border="var(--risk-low-border)"
          withDot
          size={size}
        >
          {t('datasources.create.driver_status_ready')}
        </Pill>
      );
    case 'AVAILABLE':
      return (
        <Pill
          fg="var(--accent)"
          bg="var(--accent-bg)"
          border="var(--accent-border)"
          withDot
          size={size}
        >
          {t('datasources.create.driver_status_available')}
        </Pill>
      );
    case 'UNAVAILABLE':
    default:
      return (
        <Pill
          fg="var(--risk-high)"
          bg="var(--risk-high-bg)"
          border="var(--risk-high-border)"
          withDot
          size={size}
        >
          {t('datasources.create.driver_status_unavailable')}
        </Pill>
      );
  }
}
