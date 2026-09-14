import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import type { SqlReviewEvaluation } from '@/types/api';

const { evaluateSqlReviewMock } = vi.hoisted(() => ({ evaluateSqlReviewMock: vi.fn() }));

vi.mock('@/api/sqlReview', async () => {
  const actual = await vi.importActual<typeof import('@/api/sqlReview')>('@/api/sqlReview');
  return { ...actual, evaluateSqlReview: evaluateSqlReviewMock };
});

const { useSqlReviewLint } = await import('../useSqlReviewLint');

const evaluation: SqlReviewEvaluation = {
  applicable: true,
  findings: [
    { rule_id: 'select_star', severity: 'BLOCK', statement_index: 0, line_number: 1, message: 'star' },
    { rule_id: 'missing_limit_on_select', severity: 'WARN', statement_index: 0, message: 'limit' },
  ],
};

function invalidSql(): AxiosError {
  const response = {
    data: { error: 'INVALID_SQL' },
    status: 422,
    statusText: '',
    headers: {},
    config: {} as never,
  } as AxiosResponse;
  return new AxiosError('Unprocessable', undefined, undefined, undefined, response);
}

// One client per test (see beforeEach), not per render — a rerender must keep the same cache.
let client: QueryClient;
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('useSqlReviewLint (#865)', () => {
  beforeEach(() => {
    evaluateSqlReviewMock.mockReset();
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });

  describe('debounce gate (fake timers)', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    it('never requests on an empty editor', () => {
      const { result } = renderHook(
        () => useSqlReviewLint({ datasourceId: 'ds-1', dbType: 'POSTGRESQL', sql: '   ' }),
        { wrapper },
      );
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(evaluateSqlReviewMock).not.toHaveBeenCalled();
      expect(result.current).toEqual({
        supported: true,
        evaluating: false,
        findings: [],
        blockingCount: 0,
        unparseable: false,
      });
    });

    it('never requests without a selected datasource', () => {
      renderHook(
        () => useSqlReviewLint({ datasourceId: undefined, dbType: 'POSTGRESQL', sql: 'SELECT 1' }),
        { wrapper },
      );
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(evaluateSqlReviewMock).not.toHaveBeenCalled();
    });

    it('never requests for an engine the rule catalog does not cover', () => {
      const { result } = renderHook(
        () => useSqlReviewLint({ datasourceId: 'ds-m', dbType: 'MONGODB', sql: 'db.users.find()' }),
        { wrapper },
      );
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(evaluateSqlReviewMock).not.toHaveBeenCalled();
      expect(result.current.supported).toBe(false);
    });

    it('never requests a draft over the backend size limit', () => {
      renderHook(
        () =>
          useSqlReviewLint({
            datasourceId: 'ds-1',
            dbType: 'POSTGRESQL',
            sql: 'x'.repeat(100_001),
          }),
        { wrapper },
      );
      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(evaluateSqlReviewMock).not.toHaveBeenCalled();
    });

    it('waits for the typing pause before requesting, then sends the raw SQL', () => {
      evaluateSqlReviewMock.mockResolvedValue(evaluation);
      const { rerender } = renderHook(
        ({ sql }) => useSqlReviewLint({ datasourceId: 'ds-1', dbType: 'POSTGRESQL', sql }),
        { wrapper, initialProps: { sql: '' } },
      );
      rerender({ sql: '\nSELECT *' });
      act(() => {
        vi.advanceTimersByTime(399);
      });
      expect(evaluateSqlReviewMock).not.toHaveBeenCalled();
      act(() => {
        vi.advanceTimersByTime(1);
      });
      expect(evaluateSqlReviewMock).toHaveBeenCalledTimes(1);
      expect(evaluateSqlReviewMock).toHaveBeenCalledWith({ datasource_id: 'ds-1', sql: '\nSELECT *' });
    });
  });

  it('surfaces findings and the blocking count once the evaluation lands', async () => {
    evaluateSqlReviewMock.mockResolvedValue(evaluation);
    const { result } = renderHook(
      () =>
        useSqlReviewLint({ datasourceId: 'ds-1', dbType: 'POSTGRESQL', sql: 'SELECT *', debounceMs: 0 }),
      { wrapper },
    );
    expect(result.current.evaluating).toBe(true);
    await waitFor(() => expect(result.current.findings).toHaveLength(2));
    expect(result.current).toMatchObject({
      supported: true,
      evaluating: false,
      blockingCount: 1,
      unparseable: false,
    });
  });

  it('keeps the previous findings on screen while the next draft is evaluated', async () => {
    evaluateSqlReviewMock.mockResolvedValueOnce(evaluation);
    const { result, rerender } = renderHook(
      ({ sql }) => useSqlReviewLint({ datasourceId: 'ds-1', dbType: 'POSTGRESQL', sql, debounceMs: 0 }),
      { wrapper, initialProps: { sql: 'SELECT *' } },
    );
    await waitFor(() => expect(result.current.findings).toHaveLength(2));
    evaluateSqlReviewMock.mockReturnValueOnce(new Promise(() => undefined));
    rerender({ sql: 'SELECT * FROM t' });
    await waitFor(() => expect(result.current.evaluating).toBe(true));
    expect(result.current.findings).toHaveLength(2);
    expect(evaluateSqlReviewMock).toHaveBeenCalledTimes(2);
  });

  it('reports an unparseable draft quietly on 422 INVALID_SQL', async () => {
    evaluateSqlReviewMock.mockRejectedValue(invalidSql());
    const { result } = renderHook(
      () =>
        useSqlReviewLint({ datasourceId: 'ds-1', dbType: 'POSTGRESQL', sql: 'SELEC', debounceMs: 0 }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.unparseable).toBe(true));
    expect(result.current.findings).toEqual([]);
    expect(result.current.evaluating).toBe(false);
  });

  it('yields no findings and no unparseable hint on any other failure', async () => {
    evaluateSqlReviewMock.mockRejectedValue(new Error('network'));
    const { result } = renderHook(
      () =>
        useSqlReviewLint({ datasourceId: 'ds-1', dbType: 'POSTGRESQL', sql: 'SELECT 1', debounceMs: 0 }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.evaluating).toBe(false));
    expect(result.current).toMatchObject({ findings: [], unparseable: false, supported: true });
  });

  it('turns the surface off when the backend says the engine is not applicable', async () => {
    evaluateSqlReviewMock.mockResolvedValue({ applicable: false, findings: [] });
    const { result } = renderHook(
      () =>
        useSqlReviewLint({ datasourceId: 'ds-c', dbType: 'CUSTOM', sql: 'SELECT 1', debounceMs: 0 }),
      { wrapper },
    );
    await waitFor(() => expect(result.current.evaluating).toBe(false));
    expect(result.current.supported).toBe(false);
    expect(result.current.findings).toEqual([]);
  });
});
