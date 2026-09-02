import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get },
}));

import * as api from './deploymentVersions';
import { deploymentVersionKeys } from './deploymentVersions';

const rowFixture = {
  pipeline_id: 'p-1',
  pipeline_name: 'payments-api',
  environment: { id: 'e-1', name: 'prod', tags: ['prod'], sort_order: 3 },
  current_version: '2.4.0',
  current_request_id: 'r-1',
  deployed_at: '2026-08-20T10:15:00Z',
  previous_version: '2.3.9',
  last_outcome: 'SUCCEEDED',
  drift: {
    latest_version: '2.4.1',
    latest_deployed_at: '2026-08-24T10:15:00Z',
    drifted: true,
    days_behind: 4,
    deployments_behind: 1,
  },
};

const historyFixture = {
  request_id: 'r-1',
  version: '2.4.0',
  status: 'EXECUTED',
  outcome: 'SUCCEEDED',
  outcome_reported_at: '2026-08-20T10:20:00Z',
  submitted_by: 'u-1',
  submission_reason: 'USER_SUBMITTED',
  commit_sha: 'abc1234def',
  run_url: 'https://ci.example.com/run/1',
  created_at: '2026-08-20T10:10:00Z',
  executed_at: '2026-08-20T10:15:00Z',
};

describe('api/deploymentVersions', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(deploymentVersionKeys.all).toEqual(['deployment-versions']);
    expect(deploymentVersionKeys.matrices()).toEqual(['deployment-versions', 'matrix']);
    expect(deploymentVersionKeys.matrix('p-1')).toEqual(['deployment-versions', 'matrix', 'p-1']);
    expect(deploymentVersionKeys.lists()).toEqual(['deployment-versions', 'list']);
    expect(deploymentVersionKeys.list({ tag: 'prod' })).toEqual([
      'deployment-versions',
      'list',
      { tag: 'prod' },
    ]);
    expect(deploymentVersionKeys.histories()).toEqual(['deployment-versions', 'history']);
    expect(deploymentVersionKeys.history('p-1', 'e-1', { page: 2 })).toEqual([
      'deployment-versions',
      'history',
      'p-1',
      'e-1',
      { page: 2 },
    ]);
  });

  it('reads the per-pipeline matrix as a bare array', async () => {
    get.mockResolvedValue({ data: [rowFixture] });
    const rows = await api.listPipelineEnvironmentVersions('p-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/environment-versions');
    expect(rows).toHaveLength(1);
    expect(rows[0]?.environment.name).toBe('prod');
  });

  it('sends every org-wide filter', async () => {
    get.mockResolvedValue({ data: { content: [rowFixture], total_elements: 1 } });
    const page = await api.listDeploymentEnvironmentVersions({
      pipeline_id: 'p-1',
      tag: 'prod',
      environment: 'prod',
      drifted: true,
      page: 1,
      size: 20,
    });
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-environment-versions', {
      params: {
        pipeline_id: 'p-1',
        tag: 'prod',
        environment: 'prod',
        drifted: true,
        page: 1,
        size: 20,
      },
    });
    expect(page.content).toHaveLength(1);
  });

  it('keeps drifted=false — the "up to date only" filter', async () => {
    get.mockResolvedValue({ data: { content: [], total_elements: 0 } });
    await api.listDeploymentEnvironmentVersions({ drifted: false });
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-environment-versions', {
      params: { drifted: false },
    });
  });

  it('omits absent org-wide filters entirely', async () => {
    get.mockResolvedValue({ data: { content: [], total_elements: 0 } });
    await api.listDeploymentEnvironmentVersions();
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-environment-versions', { params: {} });
  });

  it('reads environment history with a status filter', async () => {
    get.mockResolvedValue({ data: { content: [historyFixture], total_elements: 1 } });
    const page = await api.listDeploymentEnvironmentHistory('p-1', 'e-1', {
      status: 'EXECUTED',
      page: 0,
      size: 20,
    });
    expect(get).toHaveBeenCalledWith(
      '/api/v1/deployment-pipelines/p-1/environments/e-1/history',
      { params: { status: 'EXECUTED', page: 0, size: 20 } },
    );
    expect(page.content[0]?.version).toBe('2.4.0');
  });

  it('reads environment history with no filters', async () => {
    get.mockResolvedValue({ data: { content: [], total_elements: 0 } });
    await api.listDeploymentEnvironmentHistory('p-1', 'e-1');
    expect(get).toHaveBeenCalledWith(
      '/api/v1/deployment-pipelines/p-1/environments/e-1/history',
      { params: {} },
    );
  });
});
