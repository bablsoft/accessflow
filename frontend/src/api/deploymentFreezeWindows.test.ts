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

import * as api from './deploymentFreezeWindows';
import { deploymentFreezeWindowKeys } from './deploymentFreezeWindows';

const windowFixture = {
  id: 'fw-1',
  pipeline_id: null,
  environment_id: null,
  starts_at: null,
  ends_at: null,
  days_of_week: [6, 7],
  start_time: '00:00',
  end_time: '23:59',
  timezone: 'UTC',
  behavior: 'HOLD',
  reason: 'weekend freeze',
  enabled: true,
  created_at: '2026-08-20T10:15:00Z',
};

describe('api/deploymentFreezeWindows', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(deploymentFreezeWindowKeys.list({ size: 200 })).toEqual([
      'deployment-freeze-windows',
      'list',
      { size: 200 },
    ]);
    expect(deploymentFreezeWindowKeys.detail('fw-1')).toEqual([
      'deployment-freeze-windows',
      'detail',
      'fw-1',
    ]);
  });

  it('lists freeze windows with pagination params', async () => {
    get.mockResolvedValue({ data: { content: [windowFixture], total_elements: 1 } });
    const page = await api.listDeploymentFreezeWindows({ page: 0, size: 200 });
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-freeze-windows', {
      params: { page: 0, size: 200 },
    });
    expect(page.content).toHaveLength(1);
  });

  it('gets a freeze window', async () => {
    get.mockResolvedValue({ data: windowFixture });
    const w = await api.getDeploymentFreezeWindow('fw-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-freeze-windows/fw-1');
    expect(w.behavior).toBe('HOLD');
  });

  it('creates a freeze window', async () => {
    post.mockResolvedValue({ data: windowFixture });
    await api.createDeploymentFreezeWindow({
      behavior: 'HOLD',
      days_of_week: [6, 7],
      start_time: '00:00',
      end_time: '23:59',
      timezone: 'UTC',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-freeze-windows', {
      behavior: 'HOLD',
      days_of_week: [6, 7],
      start_time: '00:00',
      end_time: '23:59',
      timezone: 'UTC',
    });
  });

  it('updates a freeze window with a full-replacement record', async () => {
    put.mockResolvedValue({ data: windowFixture });
    await api.updateDeploymentFreezeWindow('fw-1', { behavior: 'REJECT', enabled: false });
    expect(put).toHaveBeenCalledWith('/api/v1/deployment-freeze-windows/fw-1', {
      behavior: 'REJECT',
      enabled: false,
    });
  });

  it('deletes a freeze window', async () => {
    del.mockResolvedValue({ data: undefined });
    await api.deleteDeploymentFreezeWindow('fw-1');
    expect(del).toHaveBeenCalledWith('/api/v1/deployment-freeze-windows/fw-1');
  });
});
