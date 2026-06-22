import type { AccessGrantStatus, BehaviorAnomalyStatus, QueryStatus } from '@/types/api';
import type { ColorTriple } from './riskColors';

export const anomalyStatusColor = (status: BehaviorAnomalyStatus): ColorTriple => {
  switch (status) {
    case 'OPEN':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'ACKNOWLEDGED':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
    case 'DISMISSED':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};

export const accessGrantStatusColor = (status: AccessGrantStatus): ColorTriple => {
  switch (status) {
    case 'PENDING':
      return { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' };
    case 'APPROVED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'REJECTED':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'EXPIRED':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
    case 'REVOKED':
    case 'CANCELLED':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};

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
    case 'TIMED_OUT':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
    case 'CANCELLED':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};
