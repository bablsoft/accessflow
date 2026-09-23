import type {
  AccessGrantStatus,
  AttestationCampaignStatus,
  AttestationItemDecision,
  BehaviorAnomalyStatus,
  BreakGlassEventStatus,
  DecisionStepOutcome,
  DeploymentOutcome,
  DeploymentRollbackReviewStatus,
  GrantUsageRecommendation,
  QueryStatus,
  RequestGroupItemStatus,
  RequestGroupStatus,
  SchemaChangeLadderRungState,
  SchemaChangePromotionStatus,
  SchemaChangeSetStatus,
  SchemaDriftFindingKind,
  SchemaDriftFindingStatus,
  StandingBypassKind,
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

/**
 * Severity ramp for a grant's revocation recommendation (#625). INSUFFICIENT_DATA is deliberately
 * neutral rather than green: "not measured yet" is not a clean bill of health.
 */
export const grantUsageRecommendationColor = (
  recommendation: GrantUsageRecommendation,
): ColorTriple => {
  switch (recommendation) {
    case 'NEVER_USED':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'STALE':
      return { fg: 'var(--risk-high)', bg: 'var(--risk-high-bg)', border: 'var(--risk-high-border)' };
    case 'OVER_SCOPED':
      return { fg: 'var(--risk-med)', bg: 'var(--risk-med-bg)', border: 'var(--risk-med-border)' };
    case 'ACTIVE':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'INSUFFICIENT_DATA':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};

/**
 * Decision-trace step outcome (#1066). A SKIP is neutral, not hidden: "this stage never ran" is an
 * answer in its own right. MATCH is warn because a matched policy changes the outcome either way.
 */
export const decisionStepOutcomeColor = (outcome: DecisionStepOutcome): ColorTriple => {
  switch (outcome) {
    case 'ALLOW':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'DENY':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'MATCH':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
    case 'NO_MATCH':
    case 'SKIP':
      return { fg: 'var(--fg-muted)', bg: 'var(--status-neutral-bg)', border: 'var(--status-neutral-border)' };
  }
};

/**
 * The two standing-bypass paths (#968), ranked by how much they skip: QUERY_ADMIN bypasses the
 * whole permission gate, break-glass only bypasses review and is compensated by a retro-review.
 */
export const standingBypassKindColor = (kind: StandingBypassKind): ColorTriple => {
  switch (kind) {
    case 'QUERY_ADMIN':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'BREAK_GLASS':
      return { fg: 'var(--risk-high)', bg: 'var(--risk-high-bg)', border: 'var(--risk-high-border)' };
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

export const deploymentOutcomeColor = (outcome: DeploymentOutcome): ColorTriple => {
  switch (outcome) {
    case 'SUCCEEDED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
    case 'FAILED':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'ROLLED_BACK':
      return { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' };
  }
};

export const deploymentRollbackReviewStatusColor = (
  status: DeploymentRollbackReviewStatus,
): ColorTriple => {
  switch (status) {
    case 'PENDING_REVIEW':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)', border: 'var(--risk-crit-border)' };
    case 'REVIEWED':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };
  }
};

/**
 * Version-drift chip (#743). Being behind the latest release is an operational fact, not a
 * failure, so a drifted row takes the warn triple and never the critical one.
 */
export const driftColor = (drifted: boolean): ColorTriple =>
  drifted
    ? { fg: 'var(--status-warn)', bg: 'var(--status-warn-bg)', border: 'var(--status-warn-border)' }
    : { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)', border: 'var(--risk-low-border)' };

// ── Schema change governance (epic #870, UI #883) ───────────────────────────

const NEUTRAL: ColorTriple = {
  fg: 'var(--fg-muted)',
  bg: 'var(--status-neutral-bg)',
  border: 'var(--status-neutral-border)',
};
const INFO: ColorTriple = {
  fg: 'var(--status-info)',
  bg: 'var(--status-info-bg)',
  border: 'var(--status-info-border)',
};
const WARN: ColorTriple = {
  fg: 'var(--status-warn)',
  bg: 'var(--status-warn-bg)',
  border: 'var(--status-warn-border)',
};
const GOOD: ColorTriple = {
  fg: 'var(--risk-low)',
  bg: 'var(--risk-low-bg)',
  border: 'var(--risk-low-border)',
};
const BAD: ColorTriple = {
  fg: 'var(--risk-crit)',
  bg: 'var(--risk-crit-bg)',
  border: 'var(--risk-crit-border)',
};

export const schemaChangeSetStatusColor = (status: SchemaChangeSetStatus): ColorTriple => {
  switch (status) {
    case 'DRAFT':
    case 'ARCHIVED':
      return NEUTRAL;
    case 'ACTIVE':
      return INFO;
  }
};

export const schemaChangePromotionStatusColor = (
  status: SchemaChangePromotionStatus,
): ColorTriple => {
  switch (status) {
    case 'PENDING':
    case 'IN_REVIEW':
    case 'APPROVED':
      return INFO;
    case 'APPLIED':
      return GOOD;
    case 'PARTIALLY_APPLIED':
      return WARN;
    case 'FAILED':
      return BAD;
    case 'CANCELLED':
      return NEUTRAL;
  }
};

export const schemaChangeLadderRungColor = (state: SchemaChangeLadderRungState): ColorTriple => {
  switch (state) {
    case 'APPLIED':
      return GOOD;
    case 'IN_PROGRESS':
    case 'PROMOTABLE':
      return INFO;
    case 'BLOCKED':
      return NEUTRAL;
  }
};

/** Drift severity: an absent or unexpected object outranks a changed attribute of one both sides have. */
export const schemaDriftFindingKindColor = (kind: SchemaDriftFindingKind): ColorTriple => {
  switch (kind) {
    case 'MISSING_IN_TARGET':
    case 'UNEXPECTED_IN_TARGET':
      return BAD;
    case 'TYPE_MISMATCH':
    case 'PRIMARY_KEY_MISMATCH':
    case 'FOREIGN_KEY_MISMATCH':
      return WARN;
    case 'NULLABILITY_MISMATCH':
      return INFO;
  }
};

export const schemaDriftFindingStatusColor = (status: SchemaDriftFindingStatus): ColorTriple => {
  switch (status) {
    case 'OPEN':
      return BAD;
    case 'ACKNOWLEDGED':
      return WARN;
    case 'RESOLVED':
      return GOOD;
  }
};
