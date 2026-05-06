import { describe, expect, it, vi, beforeEach } from 'vitest';

const login = vi.fn();
const logout = vi.fn();
const refresh = vi.fn();
vi.mock('@/api/auth', () => ({
  login: (...a: unknown[]) => login(...a),
  logout: (...a: unknown[]) => logout(...a),
  refresh: (...a: unknown[]) => refresh(...a),
}));

import { useAuthStore } from './authStore';

const sessionPayload = {
  access_token: 'tok',
  expires_in: 900,
  user: {
    id: 'u-1',
    email: 'a@b.com',
    display_name: 'A',
    role: 'ANALYST' as const,
  },
};

describe('authStore', () => {
  beforeEach(() => {
    login.mockReset();
    logout.mockReset();
    refresh.mockReset();
    useAuthStore.getState().clear();
  });

  it('login writes user and access token into the store', async () => {
    login.mockResolvedValueOnce(sessionPayload);
    await useAuthStore.getState().login('a@b.com', 'secret');
    expect(useAuthStore.getState().user).toEqual(sessionPayload.user);
    expect(useAuthStore.getState().accessToken).toBe('tok');
    expect(useAuthStore.getState().isAuthenticated()).toBe(true);
  });

  it('login propagates errors and leaves the store unchanged', async () => {
    login.mockRejectedValueOnce(new Error('401'));
    await expect(useAuthStore.getState().login('a@b.com', 'wrong')).rejects.toThrow('401');
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('logout clears the session even when the network call fails', async () => {
    useAuthStore.getState().setSession(sessionPayload);
    logout.mockRejectedValueOnce(new Error('500'));
    await useAuthStore.getState().logout();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('setSession + clear round-trip', () => {
    useAuthStore.getState().setSession(sessionPayload);
    expect(useAuthStore.getState().isAuthenticated()).toBe(true);
    useAuthStore.getState().clear();
    expect(useAuthStore.getState().isAuthenticated()).toBe(false);
  });
});
