import type { QueryStatus } from '@/types/api';
import type { ColorTriple } from './riskColors';

export const statusColor = (status: QueryStatus): ColorTriple => {
  switch (status) {
    case 'PENDING_AI':
    case 'PENDING_REVIEW':
      return { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' };
    case 'APPROVED':
    case 'EXECUTED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'REJECTED':
    case 'FAILED':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'CANCELLED':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};

export const statusLabel = (s: QueryStatus): string => s.replace('_', ' ');
