import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put, delete: del },
}));

import * as routingPoliciesApi from './routingPolicies';
import type { RoutingPolicy } from '@/types/api';

const policyFixture: RoutingPolicy = {
  id: 'rp-1',
  organization_id: 'org-1',
  datasource_id: null,
  name: 'Block payroll deletes',
  description: null,
  priority: 1,
  enabled: true,
  condition: { type: 'query_type', any_of: ['DELETE'] },
  action: 'AUTO_REJECT',
  required_approvals: null,
  reason: 'protected',
  version: 0,
  created_at: '2026-06-01T10:00:00Z',
  updated_at: '2026-06-01T10:00:00Z',
};

describe('api/routingPolicies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('listRoutingPolicies returns the plain array', async () => {
    get.mockResolvedValueOnce({ data: [policyFixture] });
    const result = await routingPoliciesApi.listRoutingPolicies();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/routing-policies');
    expect(result).toEqual([policyFixture]);
  });

  it('getRoutingPolicy GETs /{id}', async () => {
    get.mockResolvedValueOnce({ data: policyFixture });
    const result = await routingPoliciesApi.getRoutingPolicy('rp-1');
    expect(get).toHaveBeenCalledWith('/api/v1/admin/routing-policies/rp-1');
    expect(result.id).toBe('rp-1');
  });

  it('createRoutingPolicy POSTs the body', async () => {
    post.mockResolvedValueOnce({ data: policyFixture });
    const payload = {
      name: 'Block payroll deletes',
      priority: 1,
      enabled: true,
      action: 'AUTO_REJECT' as const,
      condition: { type: 'query_type' as const, any_of: ['DELETE' as const] },
    };
    await routingPoliciesApi.createRoutingPolicy(payload);
    expect(post).toHaveBeenCalledWith('/api/v1/admin/routing-policies', payload);
  });

  it('updateRoutingPolicy PUTs /{id} with the body', async () => {
    put.mockResolvedValueOnce({ data: policyFixture });
    const payload = {
      name: 'Renamed',
      priority: 1,
      enabled: false,
      action: 'AUTO_REJECT' as const,
      condition: { type: 'query_type' as const, any_of: ['DELETE' as const] },
    };
    await routingPoliciesApi.updateRoutingPolicy('rp-1', payload);
    expect(put).toHaveBeenCalledWith('/api/v1/admin/routing-policies/rp-1', payload);
  });

  it('deleteRoutingPolicy DELETEs /{id}', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await routingPoliciesApi.deleteRoutingPolicy('rp-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/routing-policies/rp-1');
  });

  it('reorderRoutingPolicies PUTs /reorder with ordered_ids', async () => {
    put.mockResolvedValueOnce({ data: [policyFixture] });
    await routingPoliciesApi.reorderRoutingPolicies(['rp-2', 'rp-1']);
    expect(put).toHaveBeenCalledWith('/api/v1/admin/routing-policies/reorder', {
      ordered_ids: ['rp-2', 'rp-1'],
    });
  });

  it('routingPolicyKeys produce stable factory output', () => {
    expect(routingPoliciesApi.routingPolicyKeys.all).toEqual(['routingPolicies']);
    expect(routingPoliciesApi.routingPolicyKeys.lists()).toEqual(['routingPolicies', 'list']);
    expect(routingPoliciesApi.routingPolicyKeys.detail('rp-1')).toEqual([
      'routingPolicies',
      'detail',
      'rp-1',
    ]);
  });
});
