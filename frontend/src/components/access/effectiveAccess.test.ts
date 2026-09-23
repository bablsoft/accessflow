import { describe, expect, it } from 'vitest';
import type { EffectiveAccessRow } from '@/types/api';
import { hasQueryAdminBypass } from './effectiveAccess';

const base: EffectiveAccessRow = {
  user_id: 'u-1',
  email: 'a@x.io',
  granted: true,
  can_break_glass: false,
  sources: [],
};

describe('hasQueryAdminBypass', () => {
  it('is true only when a QUERY_ADMIN_BYPASS source is present', () => {
    expect(hasQueryAdminBypass(base)).toBe(false);
    expect(
      hasQueryAdminBypass({
        ...base,
        sources: [{ kind: 'QUERY_ADMIN_BYPASS', grants_capability: true, pre_approve_queries: false }],
      }),
    ).toBe(true);
  });
});
