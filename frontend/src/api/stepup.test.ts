import { describe, expect, it, vi, beforeEach } from 'vitest';

const { post } = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { post },
}));

import { requestStepUp } from './stepup';

describe('api/stepup', () => {
  beforeEach(() => post.mockReset());

  it('posts a password credential', async () => {
    post.mockResolvedValueOnce({ data: { step_up_token: 'tok', expires_at: '2026-06-22T00:05:00Z' } });
    const result = await requestStepUp({ password: 'pw' });
    expect(post).toHaveBeenCalledWith('/api/v1/auth/step-up', { password: 'pw' });
    expect(result.step_up_token).toBe('tok');
  });

  it('posts a totp_code credential', async () => {
    post.mockResolvedValueOnce({ data: { step_up_token: 'tok', expires_at: 'x' } });
    await requestStepUp({ totpCode: '123456' });
    expect(post).toHaveBeenCalledWith('/api/v1/auth/step-up', { totp_code: '123456' });
  });

  it('omits empty fields', async () => {
    post.mockResolvedValueOnce({ data: { step_up_token: 'tok', expires_at: 'x' } });
    await requestStepUp({});
    expect(post).toHaveBeenCalledWith('/api/v1/auth/step-up', {});
  });
});
