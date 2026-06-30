import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { RequestGroupStatus } from '@/types/api';
import { requestGroupStatusColor } from '@/utils/statusColors';
import { requestGroupStatusLabel } from '@/utils/enumLabels';

export function RequestGroupStatusPill({
  status,
  size,
}: {
  status: RequestGroupStatus;
  size?: 'sm' | 'md';
}) {
  const { t } = useTranslation();
  const c = requestGroupStatusColor(status);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {requestGroupStatusLabel(t, status)}
    </Pill>
  );
}
