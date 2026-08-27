import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as api from './deploymentRequests';
import { deploymentKeys } from './deploymentRequests';

const requestFixture = {
  id: 'd-1',
  pipeline_id: 'p-1',
  pipeline_name: 'payments-api',
  environment_name: 'production',
  version: '2.4.1',
  status: 'PENDING_REVIEW',
  decisions: [],
};

describe('api/deploymentRequests', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(deploymentKeys.list({ status: 'APPROVED' })).toEqual([
      'deployments',
      'list',
      { status: 'APPROVED' },
    ]);
    expect(deploymentKeys.detail('d-1')).toEqual(['deployments', 'detail', 'd-1']);
    expect(deploymentKeys.gate('d-1')).toEqual(['deployments', 'detail', 'd-1', 'gate']);
  });

  it('lists deployment requests with every filter', async () => {
    get.mockResolvedValue({ data: { content: [requestFixture], total_elements: 1 } });
    const page = await api.listDeploymentRequests({
      status: 'PENDING_REVIEW',
      pipeline_id: 'p-1',
      environment: 'production',
      version: '2.4.1',
      submitted_by: 'u-1',
      from: '2026-08-01T00:00:00Z',
      to: '2026-08-31T00:00:00Z',
      page: 1,
      size: 50,
    });
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-requests', {
      params: {
        status: 'PENDING_REVIEW',
        pipeline_id: 'p-1',
        environment: 'production',
        version: '2.4.1',
        submitted_by: 'u-1',
        from: '2026-08-01T00:00:00Z',
        to: '2026-08-31T00:00:00Z',
        page: 1,
        size: 50,
      },
    });
    expect(page.content).toHaveLength(1);
  });

  it('omits absent filters', async () => {
    get.mockResolvedValue({ data: { content: [] } });
    await api.listDeploymentRequests();
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-requests', { params: {} });
  });

  it('gets a deployment request', async () => {
    get.mockResolvedValue({ data: requestFixture });
    const r = await api.getDeploymentRequest('d-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-requests/d-1');
    expect(r.id).toBe('d-1');
  });

  it('cancels a deployment request', async () => {
    post.mockResolvedValue({ data: undefined });
    await api.cancelDeploymentRequest('d-1');
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-requests/d-1/cancel');
  });

  it('fetches the gate status by request id', async () => {
    get.mockResolvedValue({
      data: { request_id: 'd-1', status: 'APPROVED', releasable: true, frozen: false },
    });
    const gate = await api.getDeploymentGate('d-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-gate', {
      params: { request_id: 'd-1' },
    });
    expect(gate.releasable).toBe(true);
  });
});
