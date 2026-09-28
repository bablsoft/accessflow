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

import * as decisionHooksApi from './decisionHooks';
import type { DecisionHook, DecisionHookWriteRequest } from '@/types/api';

const hook: DecisionHook = {
  id: 'dh-1',
  organization_id: 'org-1',
  datasource_id: null,
  name: 'OPA',
  endpoint_url: 'https://opa.example.com/v1/data',
  timeout_ms: 2000,
  include_sql: false,
  enabled: true,
  secret_configured: true,
  version: 0,
  created_at: '2026-09-28T10:00:00Z',
  updated_at: '2026-09-28T10:00:00Z',
};

const payload: DecisionHookWriteRequest = {
  name: 'OPA',
  datasource_id: null,
  endpoint_url: 'https://opa.example.com/v1/data',
  timeout_ms: 2000,
  secret: '0123456789abcdef0123456789abcdef',
  include_sql: false,
  enabled: true,
};

describe('api/decisionHooks', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('listDecisionHooks returns the plain array', async () => {
    get.mockResolvedValueOnce({ data: [hook] });
    expect(await decisionHooksApi.listDecisionHooks()).toEqual([hook]);
    expect(get).toHaveBeenCalledWith('/api/v1/admin/decision-hooks');
  });

  it('createDecisionHook POSTs the body', async () => {
    post.mockResolvedValueOnce({ data: hook });
    expect(await decisionHooksApi.createDecisionHook(payload)).toEqual(hook);
    expect(post).toHaveBeenCalledWith('/api/v1/admin/decision-hooks', payload);
  });

  it('updateDecisionHook PUTs /{id}', async () => {
    put.mockResolvedValueOnce({ data: hook });
    await decisionHooksApi.updateDecisionHook('dh-1', payload);
    expect(put).toHaveBeenCalledWith('/api/v1/admin/decision-hooks/dh-1', payload);
  });

  it('deleteDecisionHook DELETEs /{id}', async () => {
    del.mockResolvedValueOnce({});
    await decisionHooksApi.deleteDecisionHook('dh-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/decision-hooks/dh-1');
  });

  it('testDecisionHook POSTs /{id}/test and returns the outcome', async () => {
    post.mockResolvedValueOnce({ data: { outcome: 'ALLOW', latency_ms: 5 } });
    const result = await decisionHooksApi.testDecisionHook('dh-1');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/decision-hooks/dh-1/test');
    expect(result.outcome).toBe('ALLOW');
  });

  it('exposes stable query keys', () => {
    expect(decisionHooksApi.decisionHookKeys.lists()).toEqual(['decisionHooks', 'list']);
  });
});
