import { describe, expect, it } from 'vitest';
import {
  draftFingerprint,
  isMaskingDraftHighImpact,
  isRoutingDraftHighImpact,
  isRowSecurityDraftHighImpact,
  windowForDays,
} from './policyImpact';

describe('isRoutingDraftHighImpact', () => {
  it('flags both auto actions, which decide without a human', () => {
    expect(isRoutingDraftHighImpact({ action: 'AUTO_REJECT' })).toBe(true);
    expect(isRoutingDraftHighImpact({ action: 'AUTO_APPROVE' })).toBe(true);
  });

  it('does not flag the actions that still put a human in the loop', () => {
    expect(isRoutingDraftHighImpact({ action: 'ESCALATE' })).toBe(false);
    expect(isRoutingDraftHighImpact({ action: 'REQUIRE_APPROVALS' })).toBe(false);
  });

  it('does not flag the create form defaults, so a new policy never nudges on its own', () => {
    // ROUTING_POLICY_DEFAULT_VALUES is org-wide at priority 1 with REQUIRE_APPROVALS. Prompting
    // there would fire on every create and train the admin to dismiss the nudge.
    expect(isRoutingDraftHighImpact({ action: 'REQUIRE_APPROVALS' })).toBe(false);
  });

  it('does not flag a missing action', () => {
    expect(isRoutingDraftHighImpact({})).toBe(false);
  });
});

describe('isRowSecurityDraftHighImpact', () => {
  it('flags editing a policy that applies to every submitter', () => {
    expect(
      isRowSecurityDraftHighImpact({
        replacesPolicyId: 'p1',
        applies_to_roles: [],
        applies_to_group_ids: [],
        applies_to_user_ids: [],
      }),
    ).toBe(true);
  });

  it('never flags a create, however wide, because the Simulate button is right there', () => {
    expect(
      isRowSecurityDraftHighImpact({
        replacesPolicyId: null,
        applies_to_roles: [],
        applies_to_group_ids: [],
        applies_to_user_ids: [],
      }),
    ).toBe(false);
  });

  it('does not flag editing a scoped policy', () => {
    expect(
      isRowSecurityDraftHighImpact({
        replacesPolicyId: 'p1',
        applies_to_roles: ['ANALYST'],
        applies_to_group_ids: [],
        applies_to_user_ids: [],
      }),
    ).toBe(false);
  });

  it('treats undefined scope lists as empty', () => {
    expect(isRowSecurityDraftHighImpact({ replacesPolicyId: 'p1' })).toBe(true);
  });
});

describe('isMaskingDraftHighImpact', () => {
  it('flags editing a policy nobody is revealed by', () => {
    expect(
      isMaskingDraftHighImpact({
        replacesPolicyId: 'p1',
        reveal_to_roles: [],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
      }),
    ).toBe(true);
  });

  it('never flags a create', () => {
    expect(
      isMaskingDraftHighImpact({
        replacesPolicyId: null,
        reveal_to_roles: [],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
      }),
    ).toBe(false);
  });

  it('does not flag editing a policy with a reveal list', () => {
    expect(
      isMaskingDraftHighImpact({
        replacesPolicyId: 'p1',
        reveal_to_roles: ['ADMIN'],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
      }),
    ).toBe(false);
  });
});

describe('draftFingerprint', () => {
  it('is empty for no payload', () => {
    expect(draftFingerprint(null)).toBe('');
  });

  it('changes when the draft changes', () => {
    const base = {
      from: '2026-06-01T00:00:00Z',
      to: '2026-07-01T00:00:00Z',
      draft: {
        column_ref: 'customers.email',
        strategy: 'FULL' as const,
        strategy_params: {},
        reveal_to_roles: [],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
        enabled: true,
      },
    };
    const changed = { ...base, draft: { ...base.draft, column_ref: 'customers.ssn' } };
    expect(draftFingerprint(base)).not.toBe(draftFingerprint(changed));
  });

  it('is stable for the same payload', () => {
    const payload = {
      from: '2026-06-01T00:00:00Z',
      to: '2026-07-01T00:00:00Z',
      draft: {
        column_ref: 'customers.email',
        strategy: 'FULL' as const,
        strategy_params: {},
        reveal_to_roles: [],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
        enabled: true,
      },
    };
    expect(draftFingerprint(payload)).toBe(draftFingerprint({ ...payload }));
  });
});

describe('windowForDays', () => {
  it('ends now and starts the requested number of days earlier', () => {
    const now = new Date('2026-07-01T00:00:00Z');
    expect(windowForDays(30, now)).toEqual({
      from: '2026-06-01T00:00:00.000Z',
      to: '2026-07-01T00:00:00.000Z',
    });
  });

  it('supports the shorter presets', () => {
    const now = new Date('2026-07-08T12:00:00Z');
    expect(windowForDays(7, now).from).toBe('2026-07-01T12:00:00.000Z');
  });
});
