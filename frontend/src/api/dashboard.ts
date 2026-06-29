import { apiClient } from './client';
import type {
  DashboardSuggestions,
  DashboardSummary,
  DigestSubscription,
  MyApiRequestTrends,
  MyQueryTrends,
  MyQueryTrendsFilters,
} from '@/types/api';

const BASE = '/api/v1/dashboard';

export const dashboardKeys = {
  all: ['dashboard'] as const,
  summary: () => ['dashboard', 'summary'] as const,
  trends: (filters: MyQueryTrendsFilters) => ['dashboard', 'trends', filters] as const,
  apiRequestTrends: (filters: MyQueryTrendsFilters) =>
    ['dashboard', 'api-request-trends', filters] as const,
  suggestions: () => ['dashboard', 'suggestions'] as const,
  digestSubscription: () => ['dashboard', 'digest-subscription'] as const,
};

export async function fetchDashboardSummary(): Promise<DashboardSummary> {
  const { data } = await apiClient.get<DashboardSummary>(`${BASE}/summary`);
  return data;
}

export async function fetchMyQueryTrends(
  filters: MyQueryTrendsFilters = {},
): Promise<MyQueryTrends> {
  const params: Record<string, string> = {};
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  const { data } = await apiClient.get<MyQueryTrends>(`${BASE}/my-query-trends`, { params });
  return data;
}

export async function fetchMyApiRequestTrends(
  filters: MyQueryTrendsFilters = {},
): Promise<MyApiRequestTrends> {
  const params: Record<string, string> = {};
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  const { data } = await apiClient.get<MyApiRequestTrends>(`${BASE}/my-api-request-trends`, {
    params,
  });
  return data;
}

export async function fetchDashboardSuggestions(): Promise<DashboardSuggestions> {
  const { data } = await apiClient.get<DashboardSuggestions>(`${BASE}/suggestions`);
  return data;
}

export async function dismissDashboardSuggestion(id: string): Promise<void> {
  await apiClient.post(`${BASE}/suggestions/${encodeURIComponent(id)}/dismiss`);
}

export async function fetchDigestSubscription(): Promise<DigestSubscription> {
  const { data } = await apiClient.get<DigestSubscription>(`${BASE}/digest-subscription`);
  return data;
}

export async function setDigestSubscription(enabled: boolean): Promise<DigestSubscription> {
  const { data } = await apiClient.put<DigestSubscription>(`${BASE}/digest-subscription`, {
    enabled,
  });
  return data;
}

export type DashboardExportFormat = 'PDF' | 'CSV';

/**
 * Downloads the signed weekly-summary export (AF-498). Returns the raw bytes plus the filename
 * the server suggested via Content-Disposition so the caller can trigger a browser download.
 */
export async function exportDashboardSummary(
  format: DashboardExportFormat,
): Promise<{ blob: Blob; filename: string }> {
  const response = await apiClient.get(`${BASE}/summary/export`, {
    params: { format },
    responseType: 'blob',
  });
  const disposition = String(response.headers['content-disposition'] ?? '');
  const match = /filename="?([^"]+)"?/.exec(disposition);
  const filename = match?.[1] ?? `dashboard-summary.${format.toLowerCase()}`;
  return { blob: response.data as Blob, filename };
}
