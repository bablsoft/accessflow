import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ServiceAccountSource } from '@/types/api';
import { serviceAccountSourceLabel } from '@/utils/enumLabels';
import { Pill } from './Pill';

const COLORS: Record<ServiceAccountSource, { fg: string; bg: string; border: string }> = {
  UI: { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' },
  BOOTSTRAP: { fg: 'var(--risk-high)', bg: 'var(--risk-high-bg)', border: 'var(--risk-high-border)' },
};

/** Who declares a service account: the admin UI, or the bootstrap env spec (#871, #875). */
export function ManagedByTag({ managedBy, size = 'sm' }: { managedBy: ServiceAccountSource; size?: 'sm' | 'md' }) {
  const { t } = useTranslation();
  const c = COLORS[managedBy];
  return (
    <Tooltip title={t(`admin.service_accounts.managed_by_tooltip.${managedBy}`)}>
      <span data-testid="managed-by-tag">
        <Pill fg={c.fg} bg={c.bg} border={c.border} size={size}>
          {serviceAccountSourceLabel(t, managedBy)}
        </Pill>
      </span>
    </Tooltip>
  );
}
