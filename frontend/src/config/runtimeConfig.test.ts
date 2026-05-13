import { afterEach, describe, expect, it, vi } from 'vitest';

const importFresh = async () => {
  vi.resetModules();
  return import('./runtimeConfig');
};

const setWindowConfig = (value: { apiBaseUrl?: string; wsUrl?: string } | undefined) => {
  if (value === undefined) {
    delete (window as { __APP_CONFIG__?: unknown }).__APP_CONFIG__;
  } else {
    (window as { __APP_CONFIG__?: unknown }).__APP_CONFIG__ = value;
  }
};

describe('runtimeConfig precedence', () => {
  afterEach(() => {
    setWindowConfig(undefined);
    vi.unstubAllEnvs();
  });

  it('prefers window.__APP_CONFIG__ over env and defaults', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://env:8080');
    vi.stubEnv('VITE_WS_URL', 'ws://env:8080/ws');
    setWindowConfig({
      apiBaseUrl: 'https://runtime.example.com',
      wsUrl: 'wss://runtime.example.com/ws',
    });
    const { getApiBaseUrl, getWsUrl } = await importFresh();
    expect(getApiBaseUrl()).toBe('https://runtime.example.com');
    expect(getWsUrl()).toBe('wss://runtime.example.com/ws');
  });

  it('falls back to import.meta.env when window config is absent', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://env:8080');
    vi.stubEnv('VITE_WS_URL', 'ws://env:8080/ws');
    setWindowConfig(undefined);
    const { getApiBaseUrl, getWsUrl } = await importFresh();
    expect(getApiBaseUrl()).toBe('http://env:8080');
    expect(getWsUrl()).toBe('ws://env:8080/ws');
  });

  it('falls back to localhost defaults when neither window nor env is set', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '');
    vi.stubEnv('VITE_WS_URL', '');
    setWindowConfig(undefined);
    const { getApiBaseUrl, getWsUrl } = await importFresh();
    expect(getApiBaseUrl()).toBe('http://localhost:8080');
    expect(getWsUrl()).toBe('ws://localhost:8080/ws');
  });

  it('honours partial window overrides (apiBaseUrl only)', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://env:8080');
    vi.stubEnv('VITE_WS_URL', 'ws://env:8080/ws');
    setWindowConfig({ apiBaseUrl: 'https://only-api.example.com' });
    const { getApiBaseUrl, getWsUrl } = await importFresh();
    expect(getApiBaseUrl()).toBe('https://only-api.example.com');
    expect(getWsUrl()).toBe('ws://env:8080/ws');
  });

  it('treats empty-string overrides as unset', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://env:8080');
    vi.stubEnv('VITE_WS_URL', 'ws://env:8080/ws');
    setWindowConfig({ apiBaseUrl: '', wsUrl: '' });
    const { getApiBaseUrl, getWsUrl } = await importFresh();
    expect(getApiBaseUrl()).toBe('http://env:8080');
    expect(getWsUrl()).toBe('ws://env:8080/ws');
  });
});
