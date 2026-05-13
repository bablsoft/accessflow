import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as setupApi from './setup';

describe('api/setup', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('getSetupStatus calls the status endpoint and returns the body', async () => {
    get.mockResolvedValueOnce({ data: { setup_required: true } });
    const result = await setupApi.getSetupStatus();
    expect(get).toHaveBeenCalledWith('/api/v1/auth/setup-status');
    expect(result).toEqual({ setup_required: true });
  });

  it('submitSetup posts the payload and returns the login payload', async () => {
    const user = {
      id: 'u1',
      email: 'admin@acme.com',
      display_name: 'Acme Admin',
      role: 'ADMIN' as const,
      auth_provider: 'LOCAL' as const,
      totp_enabled: false,
      preferred_language: null,
    };
    post.mockResolvedValueOnce({
      data: {
        access_token: 'token',
        token_type: 'Bearer',
        expires_in: 900,
        user,
      },
    });
    const result = await setupApi.submitSetup({
      organization_name: 'Acme',
      email: 'admin@acme.com',
      display_name: 'Acme Admin',
      password: 'secret-pass-12',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/auth/setup', {
      organization_name: 'Acme',
      email: 'admin@acme.com',
      display_name: 'Acme Admin',
      password: 'secret-pass-12',
    });
    expect(result).toEqual({
      access_token: 'token',
      expires_in: 900,
      user,
    });
  });
});
