import type { TFunction } from 'i18next';
import type {
  ComparisonOperator,
  QueryType,
  RiskLevel,
  RoutingAction,
  RoutingCondition,
  RoutingConditionOperand,
  Weekday,
} from '@/types/api';
import {
  comparisonOperatorLabel,
  conditionOperandLabel,
  queryTypeLabel,
  riskLevelLabel,
  roleLabel,
  routingActionLabel,
  weekdayLabel,
} from '@/utils/enumLabels';

export type ConditionMatchType = 'ALL' | 'ANY';

/**
 * Flat form representation of one leaf condition. The guided builder produces a single-level
 * ALL/ANY of these rows (each optionally negated); the backend engine itself supports arbitrarily
 * nested trees for policies authored via the API or bootstrap.
 */
export interface RoutingConditionRow {
  operand: RoutingConditionOperand;
  negate: boolean;
  query_types?: QueryType[];
  table_globs?: string[];
  risk_levels?: RiskLevel[];
  score_operator?: ComparisonOperator;
  score_value?: number;
  /** Role NAMES — system or custom (AF-522). */
  roles?: string[];
  group_ids?: string[];
  weekdays?: Weekday[];
  time_start_min?: number;
  time_end_min?: number;
  bool_value?: boolean;
  cidrs?: string[];
  ua_patterns?: string[];
  tsla_operator?: ComparisonOperator;
  tsla_minutes?: number;
  est_operator?: ComparisonOperator;
  est_value?: number;
  scan_patterns?: string[];
}

export interface RoutingPolicyFormValues {
  name: string;
  description?: string | null;
  datasource_id?: string | null;
  priority: number;
  enabled: boolean;
  action: RoutingAction;
  required_approvals?: number | null;
  reason?: string | null;
  match_type: ConditionMatchType;
  conditions: RoutingConditionRow[];
}

export const ROUTING_POLICY_DEFAULT_VALUES: RoutingPolicyFormValues = {
  name: '',
  description: '',
  datasource_id: null,
  priority: 1,
  enabled: true,
  action: 'REQUIRE_APPROVALS',
  required_approvals: 2,
  reason: '',
  match_type: 'ALL',
  conditions: [{ operand: 'query_type', negate: false, query_types: ['DELETE'] }],
};

export function defaultRow(operand: RoutingConditionOperand): RoutingConditionRow {
  const base: RoutingConditionRow = { operand, negate: false };
  switch (operand) {
    case 'risk_score':
      return { ...base, score_operator: 'GTE', score_value: 80 };
    case 'time_of_day':
      return { ...base, time_start_min: 22 * 60, time_end_min: 6 * 60 };
    case 'has_where':
    case 'has_limit':
    case 'transactional':
      return { ...base, bool_value: false };
    case 'source_ip':
      return { ...base, cidrs: [] };
    case 'user_agent':
      return { ...base, ua_patterns: [] };
    case 'time_since_last_approval':
      return { ...base, tsla_operator: 'GT', tsla_minutes: 1440 };
    case 'cicd_origin':
      return { ...base, bool_value: true };
    case 'estimated_rows':
      return { ...base, est_operator: 'GT', est_value: 100000 };
    case 'scan_type':
      return { ...base, scan_patterns: [] };
    default:
      return base;
  }
}

export function actionRequiresApprovals(action: RoutingAction): boolean {
  return action === 'REQUIRE_APPROVALS' || action === 'ESCALATE';
}

/**
 * Client-side CIDR check mirroring the backend's literal-only validation (parity for the
 * source-IP condition). IPv4: four 0–255 octets + /0–32; IPv6: hex/colon literal + /0–128.
 * The server stays the source of truth (returns 422 on a malformed block).
 */
export function isCidr(value: string): boolean {
  const trimmed = value.trim();
  const slash = trimmed.indexOf('/');
  if (slash < 0) return false;
  const addr = trimmed.slice(0, slash);
  const prefixStr = trimmed.slice(slash + 1);
  if (!/^\d+$/.test(prefixStr)) return false;
  const prefix = Number(prefixStr);
  if (addr.includes(':')) {
    return /^[0-9a-fA-F:]+$/.test(addr) && prefix >= 0 && prefix <= 128;
  }
  const octets = addr.split('.');
  if (octets.length !== 4) return false;
  if (!octets.every((o) => /^\d{1,3}$/.test(o) && Number(o) <= 255)) return false;
  return prefix >= 0 && prefix <= 32;
}

function rowToLeaf(row: RoutingConditionRow): RoutingCondition {
  switch (row.operand) {
    case 'query_type':
      return { type: 'query_type', any_of: row.query_types ?? [] };
    case 'referenced_table':
      return { type: 'referenced_table', globs: row.table_globs ?? [] };
    case 'risk_level':
      return { type: 'risk_level', any_of: row.risk_levels ?? [] };
    case 'risk_score':
      return {
        type: 'risk_score',
        operator: row.score_operator ?? 'GTE',
        value: row.score_value ?? 0,
      };
    case 'requester_role':
      return { type: 'requester_role', any_of: row.roles ?? [] };
    case 'requester_group':
      return { type: 'requester_group', group_ids: row.group_ids ?? [] };
    case 'time_of_day':
      return {
        type: 'time_of_day',
        start_minute_of_day: row.time_start_min ?? 0,
        end_minute_of_day: row.time_end_min ?? 0,
      };
    case 'day_of_week':
      return { type: 'day_of_week', any_of: row.weekdays ?? [] };
    case 'has_where':
      return { type: 'has_where', expected: row.bool_value ?? false };
    case 'has_limit':
      return { type: 'has_limit', expected: row.bool_value ?? false };
    case 'transactional':
      return { type: 'transactional', expected: row.bool_value ?? false };
    case 'source_ip':
      return { type: 'source_ip', cidrs: row.cidrs ?? [] };
    case 'user_agent':
      return { type: 'user_agent', patterns: row.ua_patterns ?? [] };
    case 'time_since_last_approval':
      return {
        type: 'time_since_last_approval',
        operator: row.tsla_operator ?? 'GT',
        minutes: row.tsla_minutes ?? 0,
      };
    case 'cicd_origin':
      return { type: 'cicd_origin', expected: row.bool_value ?? false };
    case 'estimated_rows':
      return {
        type: 'estimated_rows',
        operator: row.est_operator ?? 'GT',
        value: row.est_value ?? 0,
      };
    case 'scan_type':
      return { type: 'scan_type', patterns: row.scan_patterns ?? [] };
  }
}

export function rowsToCondition(
  matchType: ConditionMatchType,
  rows: RoutingConditionRow[],
): RoutingCondition {
  const children: RoutingCondition[] = rows.map((row) => {
    const leaf = rowToLeaf(row);
    return row.negate ? { type: 'not', child: leaf } : leaf;
  });
  return matchType === 'ANY' ? { type: 'or', children } : { type: 'and', children };
}

function leafToRow(node: RoutingCondition, negate: boolean): RoutingConditionRow | null {
  switch (node.type) {
    case 'query_type':
      return { operand: 'query_type', negate, query_types: node.any_of };
    case 'referenced_table':
      return { operand: 'referenced_table', negate, table_globs: node.globs };
    case 'risk_level':
      return { operand: 'risk_level', negate, risk_levels: node.any_of };
    case 'risk_score':
      return {
        operand: 'risk_score',
        negate,
        score_operator: node.operator,
        score_value: node.value,
      };
    case 'requester_role':
      return { operand: 'requester_role', negate, roles: node.any_of };
    case 'requester_group':
      return { operand: 'requester_group', negate, group_ids: node.group_ids };
    case 'time_of_day':
      return {
        operand: 'time_of_day',
        negate,
        time_start_min: node.start_minute_of_day,
        time_end_min: node.end_minute_of_day,
      };
    case 'day_of_week':
      return { operand: 'day_of_week', negate, weekdays: node.any_of };
    case 'has_where':
      return { operand: 'has_where', negate, bool_value: node.expected };
    case 'has_limit':
      return { operand: 'has_limit', negate, bool_value: node.expected };
    case 'transactional':
      return { operand: 'transactional', negate, bool_value: node.expected };
    case 'source_ip':
      return { operand: 'source_ip', negate, cidrs: node.cidrs };
    case 'user_agent':
      return { operand: 'user_agent', negate, ua_patterns: node.patterns };
    case 'time_since_last_approval':
      return {
        operand: 'time_since_last_approval',
        negate,
        tsla_operator: node.operator,
        tsla_minutes: node.minutes,
      };
    case 'cicd_origin':
      return { operand: 'cicd_origin', negate, bool_value: node.expected };
    case 'estimated_rows':
      return {
        operand: 'estimated_rows',
        negate,
        est_operator: node.operator,
        est_value: node.value,
      };
    case 'scan_type':
      return { operand: 'scan_type', negate, scan_patterns: node.patterns };
    default:
      // Nested and/or/not (other than not-of-leaf) cannot be represented by the flat builder.
      return null;
  }
}

function childToRow(child: RoutingCondition): RoutingConditionRow | null {
  if (child.type === 'not') {
    return leafToRow(child.child, true);
  }
  return leafToRow(child, false);
}

/**
 * Best-effort conversion of a stored condition tree into the flat builder model. Returns
 * {@code supported: false} when the tree is too deeply nested for the guided builder to represent.
 */
export function conditionToForm(condition: RoutingCondition | null | undefined): {
  matchType: ConditionMatchType;
  rows: RoutingConditionRow[];
  supported: boolean;
} {
  if (!condition) {
    return { matchType: 'ALL', rows: [], supported: true };
  }
  if (condition.type === 'and' || condition.type === 'or') {
    const matchType: ConditionMatchType = condition.type === 'or' ? 'ANY' : 'ALL';
    const rows: RoutingConditionRow[] = [];
    for (const child of condition.children) {
      const row = childToRow(child);
      if (!row) {
        return { matchType, rows: [], supported: false };
      }
      rows.push(row);
    }
    return { matchType, rows, supported: true };
  }
  const single = childToRow(condition);
  if (!single) {
    return { matchType: 'ALL', rows: [], supported: false };
  }
  return { matchType: 'ALL', rows: [single], supported: true };
}

export function minutesToTime(minutes: number | undefined): string {
  const m = minutes ?? 0;
  const hh = String(Math.floor(m / 60)).padStart(2, '0');
  const mm = String(m % 60).padStart(2, '0');
  return `${hh}:${mm}`;
}

export function timeToMinutes(hour: number, minute: number): number {
  return hour * 60 + minute;
}

/** Compact, human-readable one-line summary of a condition tree for the policy table. */
export function conditionSummary(t: TFunction, condition: RoutingCondition): string {
  const { matchType, rows, supported } = conditionToForm(condition);
  if (!supported) {
    return t('admin.routing_policies.condition_advanced');
  }
  if (rows.length === 0) {
    return t('admin.routing_policies.condition_any_query');
  }
  const joiner =
    matchType === 'ANY'
      ? ` ${t('admin.routing_policies.match_any_joiner')} `
      : ` ${t('admin.routing_policies.match_all_joiner')} `;
  return rows.map((row) => rowSummary(t, row)).join(joiner);
}

function rowSummary(t: TFunction, row: RoutingConditionRow): string {
  const label = conditionOperandLabel(t, row.operand);
  const prefix = row.negate ? `${t('admin.routing_policies.not_prefix')} ` : '';
  let value: string;
  switch (row.operand) {
    case 'query_type':
      value = (row.query_types ?? []).map((v) => queryTypeLabel(t, v)).join(', ');
      break;
    case 'referenced_table':
      value = (row.table_globs ?? []).join(', ');
      break;
    case 'risk_level':
      value = (row.risk_levels ?? []).map((v) => riskLevelLabel(t, v)).join(', ');
      break;
    case 'risk_score':
      value = `${comparisonOperatorLabel(t, row.score_operator ?? 'GTE')} ${row.score_value ?? 0}`;
      break;
    case 'requester_role':
      value = (row.roles ?? []).map((v) => roleLabel(t, v)).join(', ');
      break;
    case 'requester_group':
      value = t('admin.routing_policies.group_count', { count: (row.group_ids ?? []).length });
      break;
    case 'time_of_day':
      value = `${minutesToTime(row.time_start_min)}–${minutesToTime(row.time_end_min)}`;
      break;
    case 'day_of_week':
      value = (row.weekdays ?? []).map((v) => weekdayLabel(t, v)).join(', ');
      break;
    case 'has_where':
    case 'has_limit':
    case 'cicd_origin':
    case 'transactional':
      value = row.bool_value ? t('common.yes') : t('common.no');
      break;
    case 'source_ip':
      value = (row.cidrs ?? []).join(', ');
      break;
    case 'user_agent':
      value = (row.ua_patterns ?? []).join(', ');
      break;
    case 'time_since_last_approval':
      value = `${comparisonOperatorLabel(t, row.tsla_operator ?? 'GT')} ${row.tsla_minutes ?? 0} `
        + t('admin.routing_policies.minutes_suffix');
      break;
    case 'estimated_rows':
      value = `${comparisonOperatorLabel(t, row.est_operator ?? 'GT')} ${row.est_value ?? 0}`;
      break;
    case 'scan_type':
      value = (row.scan_patterns ?? []).join(', ');
      break;
  }
  return `${prefix}${label}: ${value}`;
}

export function actionSummaryLabel(t: TFunction, action: RoutingAction): string {
  return routingActionLabel(t, action);
}
