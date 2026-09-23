import type { TFunction } from 'i18next';
import type {
  MaskingStrategy,
  QueryStatus,
  QueryType,
  RiskLevel,
  RoutingAction,
  RoutingPolicyTraceEntry,
} from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';
import {
  MASKING_STRATEGIES,
  QUERY_TYPES,
  RISK_LEVELS,
  ROUTING_ACTIONS,
  maskingStrategyLabel,
  queryStatusLabel,
  queryTypeLabel,
  riskLevelLabel,
  routingActionLabel,
} from '@/utils/enumLabels';

/** One formatted `details` entry of a decision-trace step. A list value renders one line each. */
export interface DetailRow {
  key: string;
  label: string;
  value: string | string[];
}

/**
 * Keys with a translated label under `decisionTrace.detail.*`. Anything the backend adds later still
 * renders — humanised from its snake_case name — rather than being dropped from the trace.
 */
export const KNOWN_DETAIL_KEYS = [
  'action',
  'active',
  'admin_bypass',
  'ai_analysis_enabled',
  'allowed_operations',
  'anomaly_active',
  'applied_policy_ids',
  'approver_email',
  'auto_approve_reads',
  'behavior',
  'blocking_count',
  'blocking_rule_ids',
  'can_break_glass',
  'can_read',
  'can_trigger',
  'can_write',
  'ci_cd_origin',
  'classified_from',
  'connector_name',
  'considered_grant_ids',
  'contributing_grants',
  'current',
  'db_type',
  'effective_min_approvals',
  'engine_id',
  'environment_allows_break_glass',
  'environment_name',
  'environment_required_approvals',
  'estimated_rows',
  'evaluated_at',
  'expires_at',
  'freeze_window_id',
  'frozen',
  'grant_id',
  'has_limit_clause',
  'has_where_clause',
  'limit',
  'masks',
  'matched_policy_id',
  'matched_policy_name',
  'min_approvals_required',
  'minutes_since_last_approval',
  'operation_count',
  'operation_id',
  'pipeline_name',
  'plan_approvers',
  'policies',
  'predicates',
  'protocol',
  'provider',
  'query_admin_short_circuit',
  'query_type',
  'quota_type',
  'reason_text',
  'referenced_tables',
  'rejected_tables',
  'releasable',
  'requester_group_ids',
  'requester_ip_address',
  'requester_role_name',
  'requester_user_agent',
  'require_review',
  'require_review_reads',
  'require_review_writes',
  'requires_human_approval',
  'restricted_response_field_count',
  'review_plan_id',
  'reviewers',
  'risk_level',
  'risk_score',
  'row_security_outcome',
  'scan_type',
  'scheduled_for',
  'scope',
  'sql_review_suppressed',
  'status',
  'submitter_excluded',
  'transactional',
  'verb',
  'visible_to_user',
  'write',
] as const;

const KNOWN = new Set<string>(KNOWN_DETAIL_KEYS);

// The routing step's policy list — rendered by its own table, never as a generic row. Other steps
// reuse the key (MASKING lists its matched masking policies under it) and render it generically.
export const ROUTING_POLICIES_KEY = 'policies';

const QUERY_STATUSES: readonly QueryStatus[] = [
  'PENDING_AI',
  'PENDING_REVIEW',
  'APPROVED',
  'EXECUTED',
  'REJECTED',
  'TIMED_OUT',
  'FAILED',
  'CANCELLED',
];

function includes<V extends string>(values: readonly V[], value: unknown): value is V {
  return typeof value === 'string' && (values as readonly string[]).includes(value);
}

/** Localises a backend enum value for the keys whose value set the UI already labels. */
function enumValue(key: string, value: unknown, t: TFunction): string | null {
  if (key === 'query_type' && includes<QueryType>(QUERY_TYPES, value)) return queryTypeLabel(t, value);
  if (key === 'action' && includes<RoutingAction>(ROUTING_ACTIONS, value)) {
    return routingActionLabel(t, value);
  }
  if (key === 'status' && includes<QueryStatus>(QUERY_STATUSES, value)) return queryStatusLabel(t, value);
  if (key === 'risk_level' && includes<RiskLevel>(RISK_LEVELS, value)) return riskLevelLabel(t, value);
  return null;
}

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/;

function humanise(key: string): string {
  const spaced = key.replace(/_/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function detailLabel(key: string, t: TFunction): string {
  return KNOWN.has(key) ? t(`decisionTrace.detail.${key}`) : humanise(key);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function scalar(value: unknown, t: TFunction): string {
  if (typeof value === 'boolean') return value ? t('common.yes') : t('common.no');
  if (typeof value === 'string') return ISO_INSTANT.test(value) ? fmtDate(value) : value;
  if (typeof value === 'number') return String(value);
  if (isRecord(value)) {
    return Object.entries(value)
      .map(([k, v]) => `${humanise(k)}: ${scalar(v, t)}`)
      .join(' · ');
  }
  if (Array.isArray(value)) return value.map((v) => scalar(v, t)).join(', ');
  return String(value);
}

function expiry(expiresAt: unknown, t: TFunction): string {
  return typeof expiresAt === 'string'
    ? t('decisionTrace.expires_at', { date: fmtDate(expiresAt) })
    : t('decisionTrace.never_expires');
}

/** A contributing grant of the EFFECTIVE_PERMISSION step: where it comes from and when it lapses. */
function grantLine(grant: Record<string, unknown>, t: TFunction): string {
  const source =
    grant.source_kind === 'GROUP' && typeof grant.group_name === 'string'
      ? t('decisionTrace.via_group', { group: grant.group_name })
      : grant.source_kind === 'GROUP'
        ? t('decisionTrace.source_group')
        : t('decisionTrace.source_direct');
  return `${source} · ${expiry(grant.expires_at, t)}`;
}

function reviewerLine(reviewer: Record<string, unknown>): string {
  const email = typeof reviewer.email === 'string' ? reviewer.email : '';
  const name = typeof reviewer.display_name === 'string' ? reviewer.display_name : null;
  return name && name !== email ? `${name} (${email})` : email;
}

function approverLine(rule: Record<string, unknown>, t: TFunction): string {
  const who =
    typeof rule.role === 'string'
      ? t('decisionTrace.approver_role', { role: rule.role })
      : t('decisionTrace.approver_user', { id: String(rule.user_id ?? '') });
  return rule.stage == null ? who : `${who} · ${t('decisionTrace.stage', { stage: rule.stage })}`;
}

/** A matched masking rule (query MASKING `policies`, API `masks`): which field, masked how. */
function maskLine(mask: Record<string, unknown>, t: TFunction): string | null {
  const field = mask.column_ref ?? mask.field_ref;
  if (typeof field !== 'string') return null;
  const strategy = includes<MaskingStrategy>(MASKING_STRATEGIES, mask.strategy)
    ? maskingStrategyLabel(t, mask.strategy)
    : String(mask.strategy ?? '');
  return t('decisionTrace.masked_as', { field, strategy });
}

function listValue(key: string, items: unknown[], t: TFunction): string[] {
  if (items.length === 0) return [t('decisionTrace.none')];
  return items.map((item) => {
    if (!isRecord(item)) return scalar(item, t);
    switch (key) {
      case 'contributing_grants':
        return grantLine(item, t);
      case 'reviewers':
        return reviewerLine(item);
      case 'plan_approvers':
        return approverLine(item, t);
      case 'policies':
      case 'masks':
        return maskLine(item, t) ?? scalar(item, t);
      default:
        return scalar(item, t);
    }
  });
}

/**
 * Formats a step's `details` into labelled rows, in the order the backend inserted them. Never
 * drops a key the caller does not `omit` (the routing step omits its `policies` list, which has its
 * own table), and returns `[]` for `{}` so the step still renders, just without a body.
 */
export function formatStepDetails(
  details: Record<string, unknown>,
  t: TFunction,
  omit: readonly string[] = [],
): DetailRow[] {
  return Object.entries(details)
    .filter(([key]) => !omit.includes(key))
    .map(([key, value]) => ({
      key,
      label: detailLabel(key, t),
      value: Array.isArray(value)
        ? listValue(key, value, t)
        : (enumValue(key, value, t) ?? scalar(value, t)),
    }));
}

/**
 * The ROUTING_POLICIES step's full policy list. `null` means the backend did not attach one — it
 * omits the list on refusal paths and when the AI outcome is FAILED, so "not evaluated" must be
 * shown rather than an empty table that reads as "no policies exist".
 */
export function extractRoutingPolicies(
  details: Record<string, unknown>,
): RoutingPolicyTraceEntry[] | null {
  const raw = details[ROUTING_POLICIES_KEY];
  if (!Array.isArray(raw)) return null;
  return raw.filter(isRecord).map((p) => ({
    policy_id: String(p.policy_id ?? ''),
    name: String(p.name ?? ''),
    priority: typeof p.priority === 'number' ? p.priority : 0,
    action: p.action as RoutingAction,
    required_approvals: typeof p.required_approvals === 'number' ? p.required_approvals : undefined,
    matched: p.matched === true,
    decisive: p.decisive === true,
  }));
}
