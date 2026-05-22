import { useTranslation } from 'react-i18next';
import { Pill } from './Pill';
import type { RiskLevel } from '@/types/api';
import { riskColor } from '@/utils/riskColors';
import { riskLevelLabel } from '@/utils/enumLabels';

export function RiskPill({
  level,
  score,
  size,
  failed = false,
}: {
  level: RiskLevel;
  score?: number | null;
  size?: 'sm' | 'md';
  failed?: boolean;
}) {
  const { t } = useTranslation();
  if (failed) {
    return (
      <Pill
        fg="var(--fg-muted)"
        bg="var(--bg-sunken)"
        border="var(--border)"
        size={size}
      >
        {t('risk.ai_na')}
      </Pill>
    );
  }
  const c = riskColor(level);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {riskLevelLabel(t, level)}
      {score != null && ` · ${score}`}
    </Pill>
  );
}
