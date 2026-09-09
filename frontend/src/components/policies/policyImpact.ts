import type {
  MaskingSimulationRequest,
  RoutingAction,
  RoutingSimulationRequest,
  RowSecuritySimulationRequest,
} from '@/types/api';

/**
 * Pure helpers behind the policy simulator's UI (AF-630). Kept out of the components so the rules
 * that decide "is this edit worth simulating first" are directly testable.
 */

/**
 * The nudge is deliberately rare. It fires only where saving could take access away without a
 * human seeing it happen — not on every wide policy, because the create forms *default* to
 * org-wide, front-of-chain, applies-to-everyone values, and a prompt on every create is noise the
 * admin learns to dismiss.
 */

/** The form values each policy form can hand the high-impact check. */
export interface RoutingImpactInput {
  action?: RoutingAction | null;
}

export interface RowSecurityImpactInput {
  /** Set when editing an existing policy; null on a create. */
  replacesPolicyId?: string | null;
  applies_to_roles?: string[] | null;
  applies_to_group_ids?: string[] | null;
  applies_to_user_ids?: string[] | null;
}

export interface MaskingImpactInput {
  /** Set when editing an existing policy; null on a create. */
  replacesPolicyId?: string | null;
  reveal_to_roles?: string[] | null;
  reveal_to_group_ids?: string[] | null;
  reveal_to_user_ids?: string[] | null;
}

const isEmpty = (list?: string[] | null): boolean => !list || list.length === 0;

/**
 * A routing draft is high-impact when it decides on its own. `REQUIRE_APPROVALS` and `ESCALATE`
 * still put a human in the loop — at worst they ask for more approvals — so they never nudge.
 */
export function isRoutingDraftHighImpact(values: RoutingImpactInput): boolean {
  return values.action === 'AUTO_APPROVE' || values.action === 'AUTO_REJECT';
}

/**
 * Changing a row-security policy that already applies to every submitter is where surprise lives:
 * there is no admin bypass, so a mistyped predicate locks everyone out at once. Adding a new one is
 * a deliberate act with the Simulate button right there, so a create never nudges.
 */
export function isRowSecurityDraftHighImpact(values: RowSecurityImpactInput): boolean {
  if (!values.replacesPolicyId) {
    return false;
  }
  return (
    isEmpty(values.applies_to_roles) &&
    isEmpty(values.applies_to_group_ids) &&
    isEmpty(values.applies_to_user_ids)
  );
}

/**
 * Editing a masking policy that reveals to nobody can only widen or narrow what everyone sees, and
 * narrowing is the direction that quietly un-masks data. As with row security, a create never
 * nudges.
 */
export function isMaskingDraftHighImpact(values: MaskingImpactInput): boolean {
  if (!values.replacesPolicyId) {
    return false;
  }
  return (
    isEmpty(values.reveal_to_roles) &&
    isEmpty(values.reveal_to_group_ids) &&
    isEmpty(values.reveal_to_user_ids)
  );
}

/** A simulation request without its window — the window is chosen inside the drawer, per run. */
export type DraftOnly<T extends { from: string; to: string }> = Omit<T, 'from' | 'to'>;

export type RoutingDraftPayload = DraftOnly<RoutingSimulationRequest>;
export type RowSecurityDraftPayload = DraftOnly<RowSecuritySimulationRequest>;
export type MaskingDraftPayload = DraftOnly<MaskingSimulationRequest>;

/**
 * A stable identity for a draft, so the drawer can tell whether what the user is about to save is
 * still what they simulated, and so the drawer body remounts only on a real edit.
 *
 * <p>The replay window is deliberately *not* part of it: it is picked per run inside the drawer, and
 * folding a fresh timestamp in here would make the fingerprint never equal itself — remounting the
 * results away the instant they arrived, and firing the nudge even right after a clean simulation.
 */
export function draftFingerprint(
  payload: RoutingDraftPayload | RowSecurityDraftPayload | MaskingDraftPayload | null,
): string {
  return payload ? JSON.stringify(payload) : '';
}

/** Inclusive-start, exclusive-end window ending now, for the drawer's range presets. */
export function windowForDays(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = new Date(now.getTime());
  const from = new Date(to.getTime() - days * 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: to.toISOString() };
}
