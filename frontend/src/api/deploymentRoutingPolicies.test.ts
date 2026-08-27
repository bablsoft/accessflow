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

import * as api from './deploymentRoutingPolicies';
import { deploymentRoutingPolicyKeys } from './deploymentRoutingPolicies';

const policyFixture = {
  id: 'pol-1',
  pipeline_id: null,
  name: 'auto-approve dev',
  conditions: {
    environments: ['dev'],
    providers: [],
    min_risk_level: null,
    version_globs: [],
    days_of_week: [],
    start_time: null,
    end_time: null,
    timezone: null,
  },
  action: 'AUTO_APPROVE',
  required_approvals: null,
  priority: 100,
  enabled: true,
  created_at: '2026-08-20T10:15:00Z',
};

describe('api/deploymentRoutingPolicies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds query keys', () => {
    expect(deploymentRoutingPolicyKeys.list()).toEqual(['deployment-routing-policies', 'list']);
    expect(deploymentRoutingPolicyKeys.detail('pol-1')).toEqual([
      'deployment-routing-policies',
      'detail',
      'pol-1',
    ]);
  });

  it('lists policies (unpaginated)', async () => {
    get.mockResolvedValue({ data: [policyFixture] });
    const policies = await api.listDeploymentRoutingPolicies();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/deployment-routing-policies');
    expect(policies).toHaveLength(1);
  });

  it('gets a policy', async () => {
    get.mockResolvedValue({ data: policyFixture });
    const p = await api.getDeploymentRoutingPolicy('pol-1');
    expect(get).toHaveBeenCalledWith('/api/v1/admin/deployment-routing-policies/pol-1');
    expect(p.action).toBe('AUTO_APPROVE');
  });

  it('creates a policy', async () => {
    post.mockResolvedValue({ data: policyFixture });
    await api.createDeploymentRoutingPolicy({
      name: 'auto-approve dev',
      action: 'AUTO_APPROVE',
      conditions: { environments: ['dev'] },
    });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/deployment-routing-policies', {
      name: 'auto-approve dev',
      action: 'AUTO_APPROVE',
      conditions: { environments: ['dev'] },
    });
  });

  it('updates a policy', async () => {
    put.mockResolvedValue({ data: policyFixture });
    await api.updateDeploymentRoutingPolicy('pol-1', { enabled: false, clear_pipeline: true });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/deployment-routing-policies/pol-1', {
      enabled: false,
      clear_pipeline: true,
    });
  });

  it('deletes a policy', async () => {
    del.mockResolvedValue({ data: undefined });
    await api.deleteDeploymentRoutingPolicy('pol-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/deployment-routing-policies/pol-1');
  });
});
