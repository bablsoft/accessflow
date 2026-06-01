import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { AccessGrantStatus } from '@/types/api';
import { accessGrantStatusColor } from '@/utils/statusColors';
import { accessGrantStatusLabel } from '@/utils/enumLabels';

export function AccessStatusPill({ status, size }: { status: AccessGrantStatus; size?: 'sm' | 'md' }) {
  const { t } = useTranslation();
  const c = accessGrantStatusColor(status);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {accessGrantStatusLabel(t, status)}
    </Pill>
  );
}
