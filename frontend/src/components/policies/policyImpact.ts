import type {
  MaskingSimulationRequest,
  RoutingAction,
  RoutingSimulationRequest,
  RowSecurityOperator,
  RowSecuritySimulationRequest,
} from '@/types/api';

/**
 * Pure helpers behind the policy simulator's UI (AF-630). Kept out of the components so the rules
 * that decide "is this edit worth simulating first" are directly testable.
 */

/** The form values each policy form can hand the high-impact check. */
export interface RoutingImpactInput {
  action?: RoutingAction | null;
  datasource_id?: string | null;
  priority?: number | null;
}

export interface RowSecurityImpactInput {
  operator?: RowSecurityOperator | null;
  value_type?: string | null;
  applies_to_roles?: string[] | null;
  applies_to_group_ids?: string[] | null;
  applies_to_user_ids?: string[] | null;
}

export interface MaskingImpactInput {
  column_ref?: string | null;
  reveal_to_roles?: string[] | null;
  reveal_to_group_ids?: string[] | null;
  reveal_to_user_ids?: string[] | null;
}

const isEmpty = (list?: string[] | null): boolean => !list || list.length === 0;

/**
 * A routing draft is high-impact when it decides on its own (either auto action), or when it is
 * org-wide and sits near the front of the chain, where it pre-empts more specific rules.
 */
export function isRoutingDraftHighImpact(values: RoutingImpactInput): boolean {
  if (values.action === 'AUTO_APPROVE' || values.action === 'AUTO_REJECT') {
    return true;
  }
  return !values.datasource_id && (values.priority ?? Number.MAX_SAFE_INTEGER) <= 10;
}

/**
 * A row-security draft is high-impact when it applies to everyone (empty scope is the
 * governance-safe default, which also means the widest blast radius), or when it can silently
 * resolve to a deny-all — an unresolvable variable yields no values, and the proxy then emits an
 * always-false predicate.
 */
export function isRowSecurityDraftHighImpact(values: RowSecurityImpactInput): boolean {
  const appliesToEveryone =
    isEmpty(values.applies_to_roles) &&
    isEmpty(values.applies_to_group_ids) &&
    isEmpty(values.applies_to_user_ids);
  return appliesToEveryone || values.value_type === 'VARIABLE';
}

/**
 * A masking draft is high-impact when nobody is on the reveal list, or when it is written as a bare
 * column name — persisted results carry no table, so a bare ref masks that column wherever it
 * appears.
 */
export function isMaskingDraftHighImpact(values: MaskingImpactInput): boolean {
  const revealsToNobody =
    isEmpty(values.reveal_to_roles) &&
    isEmpty(values.reveal_to_group_ids) &&
    isEmpty(values.reveal_to_user_ids);
  return revealsToNobody || !(values.column_ref ?? '').includes('.');
}

/**
 * A stable identity for a draft + window, so the drawer can tell whether what the user is about to
 * save is still what they simulated. Used only for that comparison — never sent to the server.
 */
export function draftFingerprint(
  payload:
    | RoutingSimulationRequest
    | RowSecuritySimulationRequest
    | MaskingSimulationRequest
    | null,
): string {
  return payload ? JSON.stringify(payload) : '';
}

/** Inclusive-start, exclusive-end window ending now, for the drawer's range presets. */
export function windowForDays(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = new Date(now.getTime());
  const from = new Date(to.getTime() - days * 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: to.toISOString() };
}
