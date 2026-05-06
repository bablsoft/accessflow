import { Pill } from './Pill';
import type { QueryType } from '@/types/api';

const COLORS: Record<QueryType, { fg: string; bg: string; border: string }> = {
  SELECT: { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' },
  INSERT: { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' },
  UPDATE: { fg: 'var(--risk-med)', bg: 'var(--risk-med-bg)', border: 'var(--risk-med-border)' },
  DELETE: { fg: 'var(--risk-high)', bg: 'var(--risk-high-bg)', border: 'var(--risk-high-border)' },
  DDL: { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' },
};

export function QueryTypePill({ type, size }: { type: QueryType; size?: 'sm' | 'md' }) {
  const c = COLORS[type];
  return (
    <Pill fg={c.fg} bg={c.bg} border={c.border} size={size}>
      {type}
    </Pill>
  );
}
