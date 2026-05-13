const DEFAULT_API_BASE_URL = 'http://localhost:8080';
const DEFAULT_WS_URL = 'ws://localhost:8080/ws';

const readWindowConfig = (key: 'apiBaseUrl' | 'wsUrl'): string | undefined => {
  if (typeof window === 'undefined') return undefined;
  const value = window.__APP_CONFIG__?.[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
};

const readEnv = (key: 'VITE_API_BASE_URL' | 'VITE_WS_URL'): string | undefined => {
  const value = import.meta.env[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
};

export const getApiBaseUrl = (): string =>
  readWindowConfig('apiBaseUrl') ?? readEnv('VITE_API_BASE_URL') ?? DEFAULT_API_BASE_URL;

export const getWsUrl = (): string =>
  readWindowConfig('wsUrl') ?? readEnv('VITE_WS_URL') ?? DEFAULT_WS_URL;
