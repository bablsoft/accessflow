import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({ apiClient: { get } }));

import { getJobRegistry, jobKeys, listJobExecutions } from './jobs';

describe('api/jobs', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('getJobRegistry reads the platform registry', async () => {
    get.mockResolvedValue({
      data: { scheduling_enabled: true, recording_enabled: true, summary_window: 'PT24H', jobs: [] },
    });

    const registry = await getJobRegistry();

    expect(get).toHaveBeenCalledWith('/api/v1/platform/jobs');
    expect(registry.scheduling_enabled).toBe(true);
  });

  it('listJobExecutions encodes the job name and sends only the set filters', async () => {
    get.mockResolvedValue({
      data: { content: [], page: 1, size: 20, total_elements: 0, total_pages: 0 },
    });

    await listJobExecutions('Query Job', { status: 'FAILED', page: 1, size: 20 });
    expect(get).toHaveBeenCalledWith('/api/v1/platform/jobs/Query%20Job/executions', {
      params: { status: 'FAILED', page: 1, size: 20 },
    });

    await listJobExecutions('QueryTimeoutJob');
    expect(get).toHaveBeenLastCalledWith('/api/v1/platform/jobs/QueryTimeoutJob/executions', {
      params: {},
    });
  });

  it('keys nest under one root so a refresh invalidates everything', () => {
    expect(jobKeys.registry()[0]).toBe(jobKeys.all[0]);
    expect(jobKeys.executions('A', { page: 0 })).toEqual(['platform-jobs', 'executions', 'A', { page: 0 }]);
  });
});
