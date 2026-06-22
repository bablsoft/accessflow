import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { BreakGlassEventStatus } from '@/types/api';
import { breakGlassStatusColor } from '@/utils/statusColors';
import { breakGlassStatusLabel } from '@/utils/enumLabels';

export function BreakGlassStatusPill({
  status,
  size,
}: {
  status: BreakGlassEventStatus;
  size?: 'sm' | 'md';
}) {
  const { t } = useTranslation();
  const c = breakGlassStatusColor(status);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {breakGlassStatusLabel(t, status)}
    </Pill>
  );
}
