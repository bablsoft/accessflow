import { describe, expect, it, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';

const { getDatasourceSampleRows } = vi.hoisted(() => ({
  getDatasourceSampleRows: vi.fn(),
}));

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, getDatasourceSampleRows };
});

import { useTableSample, SAMPLE_LIMIT } from '../useTableSample';

const sampleFixture = {
  columns: [{ name: 'id', type: 'uuid', restricted: false }],
  rows: [['1']],
  row_count: 1,
  truncated: false,
  duration_ms: 3,
};

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client, children });
}

describe('useTableSample', () => {
  beforeEach(() => {
    getDatasourceSampleRows.mockReset();
  });

  it('does not fetch until a table is selected', () => {
    const { result } = renderHook(() => useTableSample('ds-1', undefined, 'public'), { wrapper });
    expect(getDatasourceSampleRows).not.toHaveBeenCalled();
    expect(result.current.fetchStatus).toBe('idle');
  });

  it('does not fetch without a datasource', () => {
    const { result } = renderHook(() => useTableSample(undefined, 'users', 'public'), { wrapper });
    expect(getDatasourceSampleRows).not.toHaveBeenCalled();
    expect(result.current.fetchStatus).toBe('idle');
  });

  it('fetches with the default limit when datasource and table are set', async () => {
    getDatasourceSampleRows.mockResolvedValueOnce(sampleFixture);
    const { result } = renderHook(() => useTableSample('ds-1', 'users', 'public'), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getDatasourceSampleRows).toHaveBeenCalledWith('ds-1', {
      schema: 'public',
      table: 'users',
      limit: SAMPLE_LIMIT,
    });
    expect(result.current.data).toEqual(sampleFixture);
  });

  it('does not retry on error', async () => {
    getDatasourceSampleRows.mockRejectedValueOnce(new Error('boom'));
    const { result } = renderHook(() => useTableSample('ds-1', 'users'), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(getDatasourceSampleRows).toHaveBeenCalledTimes(1);
  });
});
