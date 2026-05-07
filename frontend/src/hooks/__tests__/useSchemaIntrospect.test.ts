import { describe, expect, it, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';

const { getDatasourceSchema } = vi.hoisted(() => ({
  getDatasourceSchema: vi.fn(),
}));

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>(
    '@/api/datasources',
  );
  return { ...actual, getDatasourceSchema };
});

import { useSchemaIntrospect } from '../useSchemaIntrospect';

const schemaFixture = {
  schemas: [
    {
      name: 'public',
      tables: [
        {
          name: 'users',
          columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
        },
      ],
    },
  ],
};

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return React.createElement(QueryClientProvider, { client, children });
}

describe('useSchemaIntrospect', () => {
  beforeEach(() => {
    getDatasourceSchema.mockReset();
  });

  it('does not fetch when datasourceId is undefined', () => {
    const { result } = renderHook(() => useSchemaIntrospect(undefined), { wrapper });
    expect(getDatasourceSchema).not.toHaveBeenCalled();
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });

  it('fetches and resolves when datasourceId is provided', async () => {
    getDatasourceSchema.mockResolvedValueOnce(schemaFixture);
    const { result } = renderHook(() => useSchemaIntrospect('ds-1'), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(getDatasourceSchema).toHaveBeenCalledWith('ds-1');
    expect(getDatasourceSchema).toHaveBeenCalledTimes(1);
    expect(result.current.data).toEqual(schemaFixture);
  });

  it('reports an error and does not retry on rejection', async () => {
    getDatasourceSchema.mockRejectedValueOnce(new Error('boom'));
    const { result } = renderHook(() => useSchemaIntrospect('ds-1'), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(getDatasourceSchema).toHaveBeenCalledTimes(1);
    expect(result.current.error?.message).toBe('boom');
  });
});
