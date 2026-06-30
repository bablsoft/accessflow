import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { RequestGroupItemStatus } from '@/types/api';
import { requestGroupItemStatusColor } from '@/utils/statusColors';
import { requestGroupItemStatusLabel } from '@/utils/enumLabels';

export function RequestGroupItemStatusPill({
  status,
  size,
}: {
  status: RequestGroupItemStatus;
  size?: 'sm' | 'md';
}) {
  const { t } = useTranslation();
  const c = requestGroupItemStatusColor(status);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {requestGroupItemStatusLabel(t, status)}
    </Pill>
  );
}
