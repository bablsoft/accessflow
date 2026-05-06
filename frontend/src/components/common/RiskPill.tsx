import { Pill } from './Pill';
import type { RiskLevel } from '@/types/api';
import { riskColor } from '@/utils/riskColors';

export function RiskPill({
  level,
  score,
  size,
}: {
  level: RiskLevel;
  score?: number | null;
  size?: 'sm' | 'md';
}) {
  const c = riskColor(level);
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} withDot size={size}>
      {level}
      {score != null && ` · ${score}`}
    </Pill>
  );
}
