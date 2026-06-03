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

import * as rlsApi from './rowSecurityPolicies';
import { rowSecurityPolicyKeys } from './rowSecurityPolicies';

const policyFixture = {
  id: 'rls-1',
  datasource_id: 'ds-1',
  table_name: 'public.orders',
  column_name: 'region',
  operator: 'EQUALS' as const,
  value_type: 'VARIABLE' as const,
  value_expression: 'user.region',
  applies_to_roles: ['ANALYST'] as const,
  applies_to_group_ids: [],
  applies_to_user_ids: [],
  enabled: true,
  created_at: '2026-06-01T10:00:00Z',
  updated_at: '2026-06-01T10:00:00Z',
};

describe('api/rowSecurityPolicies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(rowSecurityPolicyKeys.list('ds-1')).toEqual(['row-security-policies', 'list', 'ds-1']);
  });

  it('listRowSecurityPolicies GETs the datasource-scoped path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [policyFixture] } });
    const result = await rlsApi.listRowSecurityPolicies('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-security-policies');
    expect(result).toHaveLength(1);
    expect(result[0]?.table_name).toBe('public.orders');
  });

  it('createRowSecurityPolicy POSTs the snake_case body', async () => {
    post.mockResolvedValueOnce({ data: policyFixture });
    const input = {
      table_name: 'public.orders',
      column_name: 'region',
      operator: 'EQUALS' as const,
      value_type: 'VARIABLE' as const,
      value_expression: ':user.region',
      applies_to_roles: ['ANALYST'],
    };
    await rlsApi.createRowSecurityPolicy('ds-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-security-policies', input);
  });

  it('updateRowSecurityPolicy PUTs to the policy path', async () => {
    put.mockResolvedValueOnce({ data: policyFixture });
    const input = {
      table_name: 'orders',
      column_name: 'tenant',
      operator: 'NOT_EQUALS' as const,
      value_type: 'LITERAL' as const,
      value_expression: 'blocked',
    };
    await rlsApi.updateRowSecurityPolicy('ds-1', 'rls-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-security-policies/rls-1', input);
  });

  it('deleteRowSecurityPolicy DELETEs the policy path', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await rlsApi.deleteRowSecurityPolicy('ds-1', 'rls-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1/row-security-policies/rls-1');
  });
});
