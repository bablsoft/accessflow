import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '@/store/authStore';
import * as authApi from './auth';

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

const AUTH_BYPASS_PATHS = [
  '/api/v1/auth/login',
  '/api/v1/auth/refresh',
  '/api/v1/auth/setup',
  '/api/v1/auth/setup-status',
];

const isAuthBypass = (url: string | undefined): boolean =>
  !!url && AUTH_BYPASS_PATHS.some((p) => url.endsWith(p));

const isRefreshUrl = (url: string | undefined): boolean =>
  !!url && url.endsWith('/api/v1/auth/refresh');

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  withCredentials: true,
});

apiClient.interceptors.request.use((config) => {
  if (isAuthBypass(config.url)) return config;
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

let refreshPromise: Promise<string> | null = null;

const runRefresh = async (): Promise<string> => {
  const payload = await authApi.refresh();
  useAuthStore.getState().setSession(payload);
  return payload.access_token;
};

const onRefreshFailure = () => {
  useAuthStore.getState().clear();
  if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
    window.location.assign('/login');
  }
};

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as RetriableConfig | undefined;
    if (
      !original ||
      error.response?.status !== 401 ||
      original._retried ||
      isRefreshUrl(original.url)
    ) {
      return Promise.reject(error);
    }
    original._retried = true;
    try {
      if (!refreshPromise) {
        refreshPromise = runRefresh().finally(() => {
          refreshPromise = null;
        });
      }
      await refreshPromise;
    } catch (refreshError) {
      onRefreshFailure();
      return Promise.reject(refreshError);
    }
    return apiClient(original);
  },
);
