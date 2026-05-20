import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import type { AxiosRequestConfig, AxiosResponse } from 'axios';

const { refresh, getMessageApi, getNavigate } = vi.hoisted(() => ({
  refresh: vi.fn(),
  getMessageApi: vi.fn(),
  getNavigate: vi.fn(),
}));

vi.mock('./auth', () => ({
  refresh: (...a: unknown[]) => refresh(...a),
}));

vi.mock('@/utils/messageBridge', () => ({
  getMessageApi: () => getMessageApi(),
}));

vi.mock('@/utils/navigationBridge', () => ({
  getNavigate: () => getNavigate(),
}));

import { apiBaseUrl, apiClient } from './client';
import { useAuthStore } from '@/store/authStore';

interface MockResponse {
  status: number;
  data?: unknown;
}

const adapterResponses: Array<MockResponse | ((cfg: AxiosRequestConfig) => MockResponse)> = [];
const recorded: AxiosRequestConfig[] = [];

apiClient.defaults.adapter = (config: AxiosRequestConfig) => {
  recorded.push(config);
  const next = adapterResponses.shift();
  if (!next) {
    throw new Error(`Unexpected request: ${config.method?.toUpperCase()} ${config.url}`);
  }
  const resolved = typeof next === 'function' ? next(config) : next;
  const response: AxiosResponse = {
    data: resolved.data,
    status: resolved.status,
    statusText: '',
    headers: {},
    config: config as AxiosResponse['config'],
  };
  if (resolved.status >= 400) {
    return Promise.reject(
      Object.assign(new Error(`Request failed with status ${resolved.status}`), {
        isAxiosError: true,
        config,
        response,
      }),
    );
  }
  return Promise.resolve(response);
};

const sessionPayload = {
  access_token: 'new-token',
  expires_in: 900,
  user: {
    id: 'u-1',
    email: 'a@b.com',
    display_name: 'A',
    role: 'ANALYST' as const,
    auth_provider: 'LOCAL' as const,
    totp_enabled: false,
    preferred_language: null,
  },
};

const originalLocation = window.location;

describe('api/client interceptors', () => {
  beforeEach(() => {
    refresh.mockReset();
    getMessageApi.mockReset();
    getNavigate.mockReset();
    adapterResponses.length = 0;
    recorded.length = 0;
    useAuthStore.getState().clear();
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...originalLocation, pathname: '/editor', assign: vi.fn() },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: originalLocation,
    });
  });

  it('attaches Authorization header from the store', async () => {
    useAuthStore.getState().setSession(sessionPayload);
    adapterResponses.push({ status: 200, data: { ok: true } });
    await apiClient.get('/api/v1/queries');
    expect(recorded[0]!.headers!['Authorization']).toBe('Bearer new-token');
  });

  it('does not attach Authorization on /auth/login or /auth/refresh', async () => {
    useAuthStore.getState().setSession(sessionPayload);
    adapterResponses.push({ status: 200, data: {} });
    adapterResponses.push({ status: 200, data: {} });
    await apiClient.post('/api/v1/auth/login', {});
    await apiClient.post('/api/v1/auth/refresh');
    expect(recorded[0]!.headers!['Authorization']).toBeUndefined();
    expect(recorded[1]!.headers!['Authorization']).toBeUndefined();
  });

  it('on 401 refreshes once and replays the original request', async () => {
    useAuthStore.getState().setSession({ ...sessionPayload, access_token: 'stale' });
    refresh.mockResolvedValueOnce(sessionPayload);
    adapterResponses.push({ status: 401 });
    adapterResponses.push({ status: 200, data: { ok: true } });
    const result = await apiClient.get('/api/v1/queries');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(result.data).toEqual({ ok: true });
    expect(recorded).toHaveLength(2);
    expect(recorded[1]!.headers!['Authorization']).toBe('Bearer new-token');
  });

  it('coalesces concurrent 401s into a single refresh', async () => {
    useAuthStore.getState().setSession({ ...sessionPayload, access_token: 'stale' });
    let resolveRefresh: (v: typeof sessionPayload) => void = () => {};
    const refreshPromise = new Promise<typeof sessionPayload>((r) => {
      resolveRefresh = r;
    });
    refresh.mockReturnValueOnce(refreshPromise);
    adapterResponses.push({ status: 401 });
    adapterResponses.push({ status: 401 });
    adapterResponses.push({ status: 200, data: { a: 1 } });
    adapterResponses.push({ status: 200, data: { b: 2 } });

    const p1 = apiClient.get('/api/v1/a');
    const p2 = apiClient.get('/api/v1/b');
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    resolveRefresh(sessionPayload);
    const [r1, r2] = await Promise.all([p1, p2]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(r1.data).toEqual({ a: 1 });
    expect(r2.data).toEqual({ b: 2 });
  });

  it('clears the store, toasts session-expired, and navigates via the bridge when refresh fails', async () => {
    useAuthStore.getState().setSession({ ...sessionPayload, access_token: 'stale' });
    refresh.mockRejectedValueOnce(new Error('refresh-failed'));
    const errorSpy = vi.fn();
    const navigateSpy = vi.fn();
    getMessageApi.mockReturnValue({ error: errorSpy });
    getNavigate.mockReturnValue(navigateSpy);
    adapterResponses.push({ status: 401 });
    await expect(apiClient.get('/api/v1/queries')).rejects.toThrow('refresh-failed');
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(errorSpy).toHaveBeenCalledWith('Session expired');
    expect(navigateSpy).toHaveBeenCalledWith('/login', { replace: true });
    expect(window.location.assign).not.toHaveBeenCalled();
  });

  it('falls back to window.location.assign when the navigation bridge is unbound', async () => {
    useAuthStore.getState().setSession({ ...sessionPayload, access_token: 'stale' });
    refresh.mockRejectedValueOnce(new Error('refresh-failed'));
    getMessageApi.mockReturnValue({ error: vi.fn() });
    getNavigate.mockReturnValue(null);
    adapterResponses.push({ status: 401 });
    await expect(apiClient.get('/api/v1/queries')).rejects.toThrow('refresh-failed');
    expect(window.location.assign).toHaveBeenCalledWith('/login');
  });

  it('skips the redirect when already on /login', async () => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...originalLocation, pathname: '/login', assign: vi.fn() },
    });
    useAuthStore.getState().setSession({ ...sessionPayload, access_token: 'stale' });
    refresh.mockRejectedValueOnce(new Error('refresh-failed'));
    const navigateSpy = vi.fn();
    getMessageApi.mockReturnValue({ error: vi.fn() });
    getNavigate.mockReturnValue(navigateSpy);
    adapterResponses.push({ status: 401 });
    await expect(apiClient.get('/api/v1/queries')).rejects.toThrow('refresh-failed');
    expect(navigateSpy).not.toHaveBeenCalled();
    expect(window.location.assign).not.toHaveBeenCalled();
  });

  it('still clears the store when no message bridge is bound', async () => {
    useAuthStore.getState().setSession({ ...sessionPayload, access_token: 'stale' });
    refresh.mockRejectedValueOnce(new Error('refresh-failed'));
    getMessageApi.mockReturnValue(null);
    getNavigate.mockReturnValue(vi.fn());
    adapterResponses.push({ status: 401 });
    await expect(apiClient.get('/api/v1/queries')).rejects.toThrow('refresh-failed');
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('does not loop refresh when the refresh URL itself returns 401', async () => {
    adapterResponses.push({ status: 401 });
    await expect(apiClient.post('/api/v1/auth/refresh')).rejects.toMatchObject({
      response: { status: 401 },
    });
    expect(refresh).not.toHaveBeenCalled();
  });

  it('apiBaseUrl() prefers window.__APP_CONFIG__.apiBaseUrl', () => {
    const previous = (window as { __APP_CONFIG__?: unknown }).__APP_CONFIG__;
    (window as { __APP_CONFIG__?: unknown }).__APP_CONFIG__ = {
      apiBaseUrl: 'https://runtime.example.com',
    };
    try {
      expect(apiBaseUrl()).toBe('https://runtime.example.com');
    } finally {
      (window as { __APP_CONFIG__?: unknown }).__APP_CONFIG__ = previous;
    }
  });
});
