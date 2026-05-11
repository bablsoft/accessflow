import { useTranslation } from 'react-i18next';
import { Tooltip } from 'antd';
import { Pill } from '@/components/common/Pill';
import type { DriverStatus } from '@/types/api';

interface DriverStatusBadgeProps {
  status: DriverStatus;
  bundled?: boolean;
  size?: 'sm' | 'md';
}

interface Variant {
  labelKey: string;
  hintKey: string;
  fg: string;
  bg: string;
  border: string;
}

function variantFor(status: DriverStatus, bundled: boolean): Variant {
  if (status === 'READY' && bundled) {
    return {
      labelKey: 'datasources.create.driver_status_bundled',
      hintKey: 'datasources.create.driver_status_bundled_hint',
      fg: 'var(--risk-low)',
      bg: 'var(--risk-low-bg)',
      border: 'var(--risk-low-border)',
    };
  }
  if (status === 'READY') {
    return {
      labelKey: 'datasources.create.driver_status_downloaded',
      hintKey: 'datasources.create.driver_status_downloaded_hint',
      fg: 'var(--risk-low)',
      bg: 'var(--risk-low-bg)',
      border: 'var(--risk-low-border)',
    };
  }
  if (status === 'AVAILABLE') {
    return {
      labelKey: 'datasources.create.driver_status_available',
      hintKey: 'datasources.create.driver_status_available_hint',
      fg: 'var(--accent)',
      bg: 'var(--accent-bg)',
      border: 'var(--accent-border)',
    };
  }
  return {
    labelKey: 'datasources.create.driver_status_unavailable',
    hintKey: 'datasources.create.driver_status_unavailable_hint',
    fg: 'var(--risk-high)',
    bg: 'var(--risk-high-bg)',
    border: 'var(--risk-high-border)',
  };
}

export function DriverStatusBadge({ status, bundled = false, size = 'md' }: DriverStatusBadgeProps) {
  const { t } = useTranslation();
  const variant = variantFor(status, bundled);
  return (
    <Tooltip title={t(variant.hintKey)}>
      <span>
        <Pill
          fg={variant.fg}
          bg={variant.bg}
          border={variant.border}
          withDot
          size={size}
        >
          {t(variant.labelKey)}
        </Pill>
      </span>
    </Tooltip>
  );
}
