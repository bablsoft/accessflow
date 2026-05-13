/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_WS_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

interface AppRuntimeConfig {
  apiBaseUrl?: string;
  wsUrl?: string;
}

interface Window {
  __APP_CONFIG__?: AppRuntimeConfig;
}

declare module '*.css';
