import { beforeEach, describe, expect, it, vi } from 'vitest';

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }));

vi.mock('./client', () => ({ apiClient: { post: postMock } }));

const { simulateApiCall } = await import('./apiCallSimulations');

describe('apiCallSimulations api', () => {
  beforeEach(() => {
    postMock.mockReset();
  });

  it('posts the trace request and returns the envelope', async () => {
    const result = { steps: [], caveats: ['RESPONSE_SHAPE_ABSENT'] };
    postMock.mockResolvedValue({ data: result });

    const body = { user_id: 'u-1', connector_id: 'c-1', operation_id: 'listOrders' };
    await expect(simulateApiCall(body)).resolves.toEqual(result);
    expect(postMock).toHaveBeenCalledWith('/api/v1/admin/api-call-simulations', body);
  });
});
