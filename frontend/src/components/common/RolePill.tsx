import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { Role } from '@/types/api';
import { roleLabel } from '@/utils/enumLabels';

const COLORS: Record<Role, { fg: string; bg: string; border: string }> = {
  ADMIN: { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' },
  REVIEWER: { fg: 'var(--accent)', bg: 'var(--accent-bg)', border: 'var(--accent-border)' },
  ANALYST: { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' },
  READONLY: { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' },
};

export function RolePill({ role, size }: { role: Role; size?: 'sm' | 'md' }) {
  const { t } = useTranslation();
  const c = COLORS[role];
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} size={size}>
      {roleLabel(t, role)}
    </Pill>
  );
}
