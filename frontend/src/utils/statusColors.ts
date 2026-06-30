import type {
  AccessGrantStatus,
  AttestationCampaignStatus,
  AttestationItemDecision,
  BehaviorAnomalyStatus,
  BreakGlassEventStatus,
  QueryStatus,
  RequestGroupItemStatus,
  RequestGroupStatus,
} from '@/types/api';
import type { ColorTriple } from './riskColors';

export const breakGlassStatusColor = (status: BreakGlassEventStatus): ColorTriple => {
  switch (status) {
    case 'PENDING_REVIEW':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'REVIEWED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
  }
};

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

export const attestationCampaignStatusColor = (
  status: AttestationCampaignStatus,
): ColorTriple => {
  switch (status) {
    case 'SCHEDULED':
      return { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' };
    case 'OPEN':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
    case 'CLOSED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'CANCELLED':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};

export const attestationItemDecisionColor = (
  decision: AttestationItemDecision,
): ColorTriple => {
  switch (decision) {
    case 'PENDING':
      return { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' };
    case 'CERTIFIED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'REVOKED':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
  }
};

export const requestGroupStatusColor = (status: RequestGroupStatus): ColorTriple => {
  switch (status) {
    case 'DRAFT':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
    case 'PENDING_AI':
    case 'PENDING_REVIEW':
      return { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' };
    case 'EXECUTING':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
    case 'APPROVED':
    case 'EXECUTED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'PARTIALLY_EXECUTED':
    case 'TIMED_OUT':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
    case 'REJECTED':
    case 'FAILED':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'CANCELLED':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};

export const requestGroupItemStatusColor = (status: RequestGroupItemStatus): ColorTriple => {
  switch (status) {
    case 'PENDING':
      return { fg: 'var(--status-info)', bg: 'var(--status-info-bg)', border: 'var(--status-info-border)' };
    case 'EXECUTED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'FAILED':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'SKIPPED':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
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
