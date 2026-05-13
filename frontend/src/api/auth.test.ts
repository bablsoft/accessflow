import { describe, expect, it, vi, beforeEach } from 'vitest';

const { post, get } = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { post, get },
}));

import * as authApi from './auth';

const userPayload = {
  id: 'u-1',
  email: 'a@b.com',
  display_name: 'A',
  role: 'ANALYST' as const,
  preferred_language: null,
};

describe('api/auth', () => {
  beforeEach(() => {
    post.mockReset();
    get.mockReset();
  });

  it('login posts credentials and unwraps the response', async () => {
    post.mockResolvedValueOnce({
      data: { access_token: 't', token_type: 'Bearer', expires_in: 900, user: userPayload },
    });
    const result = await authApi.login('a@b.com', 'secret');
    expect(post).toHaveBeenCalledWith('/api/v1/auth/login', { email: 'a@b.com', password: 'secret' });
    expect(result).toEqual({ access_token: 't', expires_in: 900, user: userPayload });
  });

  it('refresh posts with no body and unwraps the response', async () => {
    post.mockResolvedValueOnce({
      data: { access_token: 't2', token_type: 'Bearer', expires_in: 900, user: userPayload },
    });
    const result = await authApi.refresh();
    expect(post).toHaveBeenCalledWith('/api/v1/auth/refresh');
    expect(result.access_token).toBe('t2');
  });

  it('logout posts to /auth/logout', async () => {
    post.mockResolvedValueOnce({ data: undefined });
    await authApi.logout();
    expect(post).toHaveBeenCalledWith('/api/v1/auth/logout');
  });

  it('listOAuth2Providers GETs the public providers endpoint', async () => {
    const summary = [{ provider: 'GOOGLE' as const, display_name: 'Google' }];
    get.mockResolvedValueOnce({ data: summary });
    const result = await authApi.listOAuth2Providers();
    expect(get).toHaveBeenCalledWith('/api/v1/auth/oauth2/providers');
    expect(result).toEqual(summary);
  });

  it('exchangeOAuth2Code POSTs the code and unwraps the response', async () => {
    post.mockResolvedValueOnce({
      data: { access_token: 't', token_type: 'Bearer', expires_in: 900, user: userPayload },
    });
    const result = await authApi.exchangeOAuth2Code('CODE_XYZ');
    expect(post).toHaveBeenCalledWith('/api/v1/auth/oauth2/exchange', { code: 'CODE_XYZ' });
    expect(result.access_token).toBe('t');
  });

  it('oauth2ProvidersKeys factory is stable', () => {
    expect(authApi.oauth2ProvidersKeys.all).toEqual(['oauth2Providers']);
  });
});
