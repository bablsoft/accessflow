import { describe, expect, it, vi, beforeEach } from 'vitest';
import { apiClient } from './client';
import {
  policySimulationKeys,
  simulateMaskingPolicy,
  simulateRoutingPolicy,
  simulateRowSecurityPolicy,
} from './policySimulation';
import type {
  MaskingSimulationRequest,
  RoutingSimulationRequest,
  RowSecuritySimulationRequest,
} from '@/types/api';

vi.mock('./client', () => ({ apiClient: { post: vi.fn() } }));

const post = vi.mocked(apiClient.post);

const WINDOW = { from: '2026-06-01T00:00:00Z', to: '2026-07-01T00:00:00Z' };

describe('policySimulationKeys', () => {
  it('is hierarchical and domain-prefixed', () => {
    expect(policySimulationKeys.all).toEqual(['policySimulation']);
    expect(policySimulationKeys.routing()).toEqual(['policySimulation', 'routing']);
    expect(policySimulationKeys.rowSecurity('ds-1')).toEqual([
      'policySimulation',
      'rowSecurity',
      'ds-1',
    ]);
    expect(policySimulationKeys.masking('ds-1')).toEqual(['policySimulation', 'masking', 'ds-1']);
  });

  it('scopes the datasource-bound keys so two datasources never share a cache entry', () => {
    expect(policySimulationKeys.rowSecurity('ds-1')).not.toEqual(
      policySimulationKeys.rowSecurity('ds-2'),
    );
  });
});

describe('simulateRoutingPolicy', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts the draft to the admin routing endpoint and returns the diff', async () => {
    const payload: RoutingSimulationRequest = {
      ...WINDOW,
      datasource_id: null,
      draft: {
        name: 'Block payroll deletes',
        priority: 10,
        enabled: true,
        condition: { type: 'query_type', any_of: ['DELETE'] },
        action: 'AUTO_REJECT',
      },
    };
    post.mockResolvedValueOnce({ data: { changed_count: 312 } });

    const result = await simulateRoutingPolicy(payload);

    expect(post).toHaveBeenCalledWith('/api/v1/admin/routing-policies/simulate', payload);
    expect(result).toEqual({ changed_count: 312 });
  });
});

describe('simulateRowSecurityPolicy', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts to the datasource-scoped row-security endpoint', async () => {
    const payload: RowSecuritySimulationRequest = {
      ...WINDOW,
      draft: {
        table_name: 'public.orders',
        column_name: 'tenant_id',
        operator: 'EQUALS',
        value_type: 'VARIABLE',
        value_expression: 'user.tenant',
        applies_to_roles: [],
        applies_to_group_ids: [],
        applies_to_user_ids: [],
        enabled: true,
      },
    };
    post.mockResolvedValueOnce({ data: { unclassifiable_count: 0 } });

    const result = await simulateRowSecurityPolicy('ds-1', payload);

    expect(post).toHaveBeenCalledWith(
      '/api/v1/datasources/ds-1/row-security-policies/simulate',
      payload,
    );
    expect(result).toEqual({ unclassifiable_count: 0 });
  });
});

describe('simulateMaskingPolicy', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts to the datasource-scoped masking endpoint', async () => {
    const payload: MaskingSimulationRequest = {
      ...WINDOW,
      draft: {
        column_ref: 'customers.email',
        strategy: 'FULL',
        strategy_params: {},
        reveal_to_roles: [],
        reveal_to_group_ids: [],
        reveal_to_user_ids: [],
        enabled: true,
      },
    };
    post.mockResolvedValueOnce({ data: { newly_masked_count: 130 } });

    const result = await simulateMaskingPolicy('ds-1', payload);

    expect(post).toHaveBeenCalledWith(
      '/api/v1/datasources/ds-1/masking-policies/simulate',
      payload,
    );
    expect(result).toEqual({ newly_masked_count: 130 });
  });
});
