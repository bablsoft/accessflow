import { apiClient } from './client';
import type {
  BulkDiscoveryDecisionInput,
  BulkDiscoveryDecisionResult,
  DiscoveryFindingPage,
  DiscoveryFindingStatus,
  DiscoveryScanConfig,
  UpdateDiscoveryConfigInput,
} from '@/types/api';

const base = (datasourceId: string) => `/api/v1/datasources/${datasourceId}/discovery`;

export interface DiscoveryFindingFilters {
  status?: DiscoveryFindingStatus;
  page?: number;
  size?: number;
}

export const discoveryKeys = {
  all: ['discovery'] as const,
  config: (datasourceId: string) => ['discovery', 'config', datasourceId] as const,
  findings: (datasourceId: string, filters: DiscoveryFindingFilters = {}) =>
    ['discovery', 'findings', datasourceId, filters] as const,
};

export async function fetchDiscoveryConfig(datasourceId: string): Promise<DiscoveryScanConfig> {
  const { data } = await apiClient.get<DiscoveryScanConfig>(`${base(datasourceId)}/config`);
  return data;
}

export async function updateDiscoveryConfig(
  datasourceId: string,
  input: UpdateDiscoveryConfigInput,
): Promise<DiscoveryScanConfig> {
  const { data } = await apiClient.put<DiscoveryScanConfig>(`${base(datasourceId)}/config`, input);
  return data;
}

export async function fetchDiscoveryFindings(
  datasourceId: string,
  filters: DiscoveryFindingFilters = {},
): Promise<DiscoveryFindingPage> {
  const params: Record<string, string | number> = {};
  if (filters.status) {
    params.status = filters.status;
  }
  if (typeof filters.page === 'number') {
    params.page = filters.page;
  }
  if (typeof filters.size === 'number') {
    params.size = filters.size;
  }
  const { data } = await apiClient.get<DiscoveryFindingPage>(`${base(datasourceId)}/findings`, {
    params,
  });
  return data;
}

export async function bulkDecideFindings(
  datasourceId: string,
  input: BulkDiscoveryDecisionInput,
): Promise<BulkDiscoveryDecisionResult> {
  const { data } = await apiClient.post<BulkDiscoveryDecisionResult>(
    `${base(datasourceId)}/findings/bulk-decision`,
    input,
  );
  return data;
}

export async function triggerDiscoveryScan(datasourceId: string): Promise<void> {
  await apiClient.post(`${base(datasourceId)}/scan`);
}
