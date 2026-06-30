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

import * as api from './apiConnectorMaskingPolicies';
import { apiConnectorMaskingPolicyKeys } from './apiConnectorMaskingPolicies';

const fixture = {
  id: 'mp-1',
  connector_id: 'c-1',
  matcher_type: 'JSON_PATH' as const,
  operation_id: null,
  field_ref: 'user.ssn',
  strategy: 'FULL' as const,
  strategy_params: {},
  reveal_to_roles: ['ADMIN'] as const,
  reveal_to_group_ids: [],
  reveal_to_user_ids: [],
  enabled: true,
  created_at: '2026-06-01T10:00:00Z',
  updated_at: '2026-06-01T10:00:00Z',
};

describe('api/apiConnectorMaskingPolicies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(apiConnectorMaskingPolicyKeys.list('c-1')).toEqual([
      'api-connector-masking-policies',
      'list',
      'c-1',
    ]);
  });

  it('list GETs the connector-scoped path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [fixture] } });
    const result = await api.listApiConnectorMaskingPolicies('c-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/masking-policies');
    expect(result).toHaveLength(1);
    expect(result[0]?.field_ref).toBe('user.ssn');
  });

  it('create POSTs the snake_case body', async () => {
    post.mockResolvedValueOnce({ data: fixture });
    const input = {
      matcher_type: 'SCHEMA_FIELD' as const,
      operation_id: 'getUser',
      field_ref: 'email',
      strategy: 'PARTIAL' as const,
      strategy_params: { visible_suffix: '4' },
    };
    await api.createApiConnectorMaskingPolicy('c-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/masking-policies', input);
  });

  it('update PUTs to the policy path', async () => {
    put.mockResolvedValueOnce({ data: fixture });
    const input = { matcher_type: 'REGEX' as const, field_ref: 'x', strategy: 'HASH' as const };
    await api.updateApiConnectorMaskingPolicy('c-1', 'mp-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/masking-policies/mp-1', input);
  });

  it('delete DELETEs the policy path', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await api.deleteApiConnectorMaskingPolicy('c-1', 'mp-1');
    expect(del).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/masking-policies/mp-1');
  });
});
