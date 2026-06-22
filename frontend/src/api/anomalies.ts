import { apiClient } from './client';
import type {
  AnomalyBadge,
  AnomalyListFilters,
  AnomalyPage,
  BehaviorAnomaly,
} from '@/types/api';

const ADMIN_BASE = '/api/v1/admin/anomalies';
const BADGE_BASE = '/api/v1/anomalies/badge';

export const anomalyKeys = {
  all: ['anomalies'] as const,
  lists: () => ['anomalies', 'list'] as const,
  list: (filters: AnomalyListFilters) => ['anomalies', 'list', filters] as const,
  details: () => ['anomalies', 'detail'] as const,
  detail: (id: string) => ['anomalies', 'detail', id] as const,
  badge: (datasourceId?: string) => ['anomalies', 'badge', datasourceId ?? 'all'] as const,
};

export async function listAnomalies(filters: AnomalyListFilters = {}): Promise<AnomalyPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.status) params.status = filters.status;
  if (filters.user_id) params.userId = filters.user_id;
  if (filters.datasource_id) params.datasourceId = filters.datasource_id;
  if (filters.feature) params.feature = filters.feature;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  const { data } = await apiClient.get<AnomalyPage>(ADMIN_BASE, { params });
  return data;
}

export async function getAnomaly(id: string): Promise<BehaviorAnomaly> {
  const { data } = await apiClient.get<BehaviorAnomaly>(`${ADMIN_BASE}/${id}`);
  return data;
}

export async function acknowledgeAnomaly(id: string): Promise<BehaviorAnomaly> {
  const { data } = await apiClient.post<BehaviorAnomaly>(`${ADMIN_BASE}/${id}/acknowledge`);
  return data;
}

export async function dismissAnomaly(id: string): Promise<BehaviorAnomaly> {
  const { data } = await apiClient.post<BehaviorAnomaly>(`${ADMIN_BASE}/${id}/dismiss`);
  return data;
}

export async function fetchAnomalyBadge(datasourceId?: string): Promise<AnomalyBadge> {
  const params: Record<string, string> = {};
  if (datasourceId) params.datasourceId = datasourceId;
  const { data } = await apiClient.get<AnomalyBadge>(BADGE_BASE, { params });
  return data;
}
