import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import type { JobExecutionStatus } from '@/types/api';
import { jobExecutionStatusColor } from '@/utils/statusColors';
import { jobExecutionStatusLabel } from '@/utils/enumLabels';

export function JobStatusPill({
  status,
  abandoned = false,
  size,
}: {
  status: JobExecutionStatus;
  abandoned?: boolean;
  size?: 'sm' | 'md';
}) {
  const { t } = useTranslation();
  const c = jobExecutionStatusColor(status, abandoned);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {abandoned ? t('admin.jobs.abandoned') : jobExecutionStatusLabel(t, status)}
    </Pill>
  );
}
