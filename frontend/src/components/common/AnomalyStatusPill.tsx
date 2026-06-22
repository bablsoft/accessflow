import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { BehaviorAnomalyStatus } from '@/types/api';
import { anomalyStatusColor } from '@/utils/statusColors';
import { anomalyStatusLabel } from '@/utils/enumLabels';

export function AnomalyStatusPill({
  status,
  size,
}: {
  status: BehaviorAnomalyStatus;
  size?: 'sm' | 'md';
}) {
  const { t } = useTranslation();
  const c = anomalyStatusColor(status);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {anomalyStatusLabel(t, status)}
    </Pill>
  );
}
