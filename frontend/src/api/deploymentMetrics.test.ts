import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get },
}));

import * as api from './deploymentMetrics';
import { deploymentMetricKeys } from './deploymentMetrics';

const metrics = { period: { from: 'a', to: 'b' }, daily: [] };

describe('deploymentMetrics api', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockResolvedValue({ data: metrics });
  });

  it('reads the org-wide metrics with every filter as a snake_case param', async () => {
    const data = await api.getOrganizationDeploymentMetrics({
      from: '2026-10-01T00:00:00.000Z',
      to: '2026-10-08T00:00:00.000Z',
      environment: 'prod',
      pipeline_id: 'p-1',
    });
    expect(data).toBe(metrics);
    expect(get.mock.calls[0]).toEqual([
      '/api/v1/deployment-metrics',
      {
        params: {
          from: '2026-10-01T00:00:00.000Z',
          to: '2026-10-08T00:00:00.000Z',
          environment: 'prod',
          pipeline_id: 'p-1',
        },
      },
    ]);
  });

  it('omits undefined and empty filters', async () => {
    await api.getOrganizationDeploymentMetrics();
    await api.getOrganizationDeploymentMetrics({ environment: '' });
    expect(get.mock.calls[0]?.[1]).toEqual({ params: {} });
    expect(get.mock.calls[1]?.[1]).toEqual({ params: {} });
  });

  it('reads one pipeline under its id', async () => {
    await api.getPipelineDeploymentMetrics('p-9', { environment: 'staging' });
    expect(get.mock.calls[0]).toEqual([
      '/api/v1/deployment-pipelines/p-9/metrics',
      { params: { environment: 'staging' } },
    ]);
  });

  it('reads the per-environment breakdown', async () => {
    const breakdown = { period: { from: 'a', to: 'b' }, environments: [] };
    get.mockResolvedValueOnce({ data: breakdown });
    const data = await api.getDeploymentEnvironmentMetrics({ pipeline_id: 'p-2' });
    expect(data).toBe(breakdown);
    expect(get.mock.calls[0]).toEqual([
      '/api/v1/deployment-metrics/environments',
      { params: { pipeline_id: 'p-2' } },
    ]);
  });

  it('keys each read apart', () => {
    expect(deploymentMetricKeys.all).toEqual(['deployment-metrics']);
    expect(deploymentMetricKeys.organization({ environment: 'prod' })).toEqual([
      'deployment-metrics',
      'organization',
      { environment: 'prod' },
    ]);
    expect(deploymentMetricKeys.pipeline('p-1', {})).toEqual([
      'deployment-metrics',
      'pipeline',
      'p-1',
      {},
    ]);
    expect(deploymentMetricKeys.breakdown({})).toEqual(['deployment-metrics', 'breakdown', {}]);
  });
});
