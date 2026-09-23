import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AccessSimulationResult, EffectiveAccessPage } from '@/types/api';

const { getMock, postMock } = vi.hoisted(() => ({ getMock: vi.fn(), postMock: vi.fn() }));

vi.mock('./client', () => ({ apiClient: { get: getMock, post: postMock } }));

const { simulateAccess, getEffectiveAccess, effectiveAccessKeys } = await import(
  './accessSimulations'
);

describe('accessSimulations api', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
  });

  it('posts the trace request and returns the envelope', async () => {
    const result: AccessSimulationResult = { steps: [], caveats: [] };
    postMock.mockResolvedValue({ data: result });

    const body = { user_id: 'u-1', datasource_id: 'd-1', sql: 'SELECT 1' };
    await expect(simulateAccess(body)).resolves.toEqual(result);
    expect(postMock).toHaveBeenCalledWith('/api/v1/admin/access-simulations', body);
  });

  it('sends the effective-access filters as query params', async () => {
    const page: EffectiveAccessPage = {
      content: [],
      page: 0,
      size: 20,
      total_elements: 0,
      total_pages: 0,
    };
    getMock.mockResolvedValue({ data: page });

    const filters = { datasource_id: 'd-1', table: 'public.orders', capability: 'WRITE' as const };
    await expect(getEffectiveAccess(filters)).resolves.toEqual(page);
    expect(getMock).toHaveBeenCalledWith('/api/v1/admin/effective-access', { params: filters });
  });

  it('builds hierarchical, domain-prefixed query keys', () => {
    const filters = { datasource_id: 'd-1', table: 't', capability: 'READ' as const };
    expect(effectiveAccessKeys.all).toEqual(['effective-access']);
    expect(effectiveAccessKeys.lists()).toEqual(['effective-access', 'list']);
    expect(effectiveAccessKeys.list(filters)).toEqual(['effective-access', 'list', filters]);
  });
});
