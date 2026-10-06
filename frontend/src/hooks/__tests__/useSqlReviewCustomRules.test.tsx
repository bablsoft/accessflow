import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { SqlReviewCustomRuleWriteRequest } from '@/types/api';

const api = vi.hoisted(() => ({
  listSqlReviewCustomRules: vi.fn(),
  createSqlReviewCustomRule: vi.fn(),
  updateSqlReviewCustomRule: vi.fn(),
  deleteSqlReviewCustomRule: vi.fn(),
  testSqlReviewCustomRule: vi.fn(),
}));

vi.mock('@/api/sqlReviewRules', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/sqlReviewRules')>('@/api/sqlReviewRules');
  return { ...actual, ...api };
});

const hooks = await import('../useSqlReviewCustomRules');

const payload: SqlReviewCustomRuleWriteRequest = {
  rule_id: 'custom_x_rule',
  name: 'X',
  message: 'm',
  category: 'PERFORMANCE',
  default_severity: 'WARN',
  enabled: true,
  condition: { type: 'and', children: [{ type: 'has_where', expected: false }] },
};

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const spy = vi.spyOn(client, 'invalidateQueries');
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { spy, wrapper };
}

describe('useSqlReviewCustomRules', () => {
  beforeEach(() => {
    Object.values(api).forEach((m) => m.mockReset());
  });

  it('lists rules', async () => {
    api.listSqlReviewCustomRules.mockResolvedValue([]);
    const { wrapper } = setup();
    const { result } = renderHook(() => hooks.useSqlReviewCustomRules(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });

  function expectBothInvalidated(spy: ReturnType<typeof setup>['spy']) {
    expect(spy).toHaveBeenCalledWith({ queryKey: ['sqlReviewCustomRules'] });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['sqlReview', 'rules'] });
    // A delete drops the rule's ruleset configs server-side; a stale ruleset would re-send them.
    expect(spy).toHaveBeenCalledWith({ queryKey: ['sqlReview', 'rulesets'] });
  }

  it('create invalidates the rule list, the catalog and the rulesets', async () => {
    api.createSqlReviewCustomRule.mockResolvedValue(undefined);
    const { spy, wrapper } = setup();
    const { result } = renderHook(() => hooks.useCreateSqlReviewCustomRule(), { wrapper });
    await result.current.mutateAsync(payload);
    expect(api.createSqlReviewCustomRule.mock.calls[0]?.[0]).toEqual(payload);
    expectBothInvalidated(spy);
  });

  it('update invalidates the rule list, the catalog and the rulesets', async () => {
    api.updateSqlReviewCustomRule.mockResolvedValue(undefined);
    const { spy, wrapper } = setup();
    const { result } = renderHook(() => hooks.useUpdateSqlReviewCustomRule(), { wrapper });
    await result.current.mutateAsync({ id: 'r-1', payload });
    expect(api.updateSqlReviewCustomRule).toHaveBeenCalledWith('r-1', payload);
    expectBothInvalidated(spy);
  });

  it('delete invalidates the rule list, the catalog and the rulesets', async () => {
    api.deleteSqlReviewCustomRule.mockResolvedValue(undefined);
    const { spy, wrapper } = setup();
    const { result } = renderHook(() => hooks.useDeleteSqlReviewCustomRule(), { wrapper });
    await result.current.mutateAsync('r-1');
    expect(api.deleteSqlReviewCustomRule).toHaveBeenCalledWith('r-1');
    expectBothInvalidated(spy);
  });

  it('tests a draft without invalidating anything', async () => {
    api.testSqlReviewCustomRule.mockResolvedValue({ findings: [] });
    const { spy, wrapper } = setup();
    const { result } = renderHook(() => hooks.useTestSqlReviewCustomRule(), { wrapper });
    await expect(result.current.mutateAsync({ rule: payload, sql: 'SELECT 1' })).resolves.toEqual({
      findings: [],
    });
    expect(spy).not.toHaveBeenCalled();
  });
});
