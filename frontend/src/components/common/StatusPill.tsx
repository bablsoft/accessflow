import { Pill } from './Pill';
import type { QueryStatus } from '@/types/api';
import { statusColor, statusLabel } from '@/utils/statusColors';

export function StatusPill({ status, size }: { status: QueryStatus; size?: 'sm' | 'md' }) {
  const c = statusColor(status);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {statusLabel(status)}
    </Pill>
  );
}
