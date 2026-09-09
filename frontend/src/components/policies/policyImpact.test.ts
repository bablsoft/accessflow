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
    expect(isRoutingDraftHighImpact({ action: 'AUTO_REJECT', datasource_id: 'ds', priority: 90 }))
      .toBe(true);
    expect(isRoutingDraftHighImpact({ action: 'AUTO_APPROVE', datasource_id: 'ds', priority: 90 }))
      .toBe(true);
  });

  it('flags an org-wide rule near the front of the chain', () => {
    expect(isRoutingDraftHighImpact({ action: 'ESCALATE', datasource_id: null, priority: 5 }))
      .toBe(true);
  });

  it('does not flag a datasource-scoped rule at the front', () => {
    expect(isRoutingDraftHighImpact({ action: 'ESCALATE', datasource_id: 'ds', priority: 5 }))
      .toBe(false);
  });

  it('does not flag an org-wide rule far down the chain', () => {
    expect(isRoutingDraftHighImpact({ action: 'ESCALATE', datasource_id: null, priority: 500 }))
      .toBe(false);
  });

  it('treats a missing priority as far down the chain rather than as zero', () => {
    expect(isRoutingDraftHighImpact({ action: 'REQUIRE_APPROVALS', datasource_id: null })).toBe(
      false,
    );
  });
});

describe('isRowSecurityDraftHighImpact', () => {
  it('flags an empty scope, which applies to every submitter', () => {
    expect(
      isRowSecurityDraftHighImpact({
        operator: 'EQUALS',
        value_type: 'LITERAL',
        applies_to_roles: [],
        applies_to_group_ids: [],
        applies_to_user_ids: [],
      }),
    ).toBe(true);
  });

  it('flags a variable predicate, which can silently resolve to a deny-all', () => {
    expect(
      isRowSecurityDraftHighImpact({
        operator: 'EQUALS',
        value_type: 'VARIABLE',
        applies_to_roles: ['ANALYST'],
      }),
    ).toBe(true);
  });

  it('does not flag a scoped literal predicate', () => {
    expect(
      isRowSecurityDraftHighImpact({
        operator: 'EQUALS',
        value_type: 'LITERAL',
        applies_to_roles: ['ANALYST'],
        applies_to_group_ids: [],
        applies_to_user_ids: [],
      }),
    ).toBe(false);
  });

  it('treats undefined scope lists as empty', () => {
    expect(isRowSecurityDraftHighImpact({ operator: 'EQUALS', value_type: 'LITERAL' })).toBe(true);
  });
});

describe('isMaskingDraftHighImpact', () => {
  it('flags a policy nobody is revealed by', () => {
    expect(
      isMaskingDraftHighImpact({
        column_ref: 'customers.email',
        reveal_to_roles: [],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
      }),
    ).toBe(true);
  });

  it('flags a bare column ref, which masks that name wherever it appears', () => {
    expect(isMaskingDraftHighImpact({ column_ref: 'email', reveal_to_roles: ['ADMIN'] })).toBe(true);
  });

  it('does not flag a qualified ref with a reveal list', () => {
    expect(
      isMaskingDraftHighImpact({
        column_ref: 'customers.email',
        reveal_to_roles: ['ADMIN'],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
      }),
    ).toBe(false);
  });

  it('treats a missing column ref as bare', () => {
    expect(isMaskingDraftHighImpact({ reveal_to_roles: ['ADMIN'] })).toBe(true);
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
