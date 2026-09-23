import { beforeEach, describe, expect, it, vi } from 'vitest';

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));

vi.mock('./client', () => ({ apiClient: { post: postMock } }));

const { simulateDeployment } = await import('./deploymentSimulations');

describe('deploymentSimulations api', () => {
  beforeEach(() => {
    postMock.mockReset();
  });

  it('posts the trace request and returns the envelope', async () => {
    const result = { steps: [], caveats: [], releasable: false };
    postMock.mockResolvedValue({ data: result });

    const body = {
      user_id: 'u-1',
      pipeline_id: 'p-1',
      environment_id: 'e-1',
      version: '1.2.3',
      at: '2026-09-25T18:00:00Z',
    };
    await expect(simulateDeployment(body)).resolves.toEqual(result);
    expect(postMock).toHaveBeenCalledWith('/api/v1/admin/deployment-simulations', body);
  });
});
