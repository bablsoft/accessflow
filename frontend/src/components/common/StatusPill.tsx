import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { QueryStatus } from '@/types/api';
import { statusColor } from '@/utils/statusColors';
import { queryStatusLabel } from '@/utils/enumLabels';

export function StatusPill({ status, size }: { status: QueryStatus; size?: 'sm' | 'md' }) {
  const { t } = useTranslation();
  const c = statusColor(status);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {queryStatusLabel(t, status)}
    </Pill>
  );
}
