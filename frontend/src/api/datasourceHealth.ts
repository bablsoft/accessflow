import { apiClient } from './client';
import type { DatasourceHealthPage } from '@/types/api';

const BASE = '/api/v1/admin/datasource-health';

export interface DatasourceHealthFilters {
  page?: number;
  size?: number;
}

export const datasourceHealthKeys = {
  all: ['datasource-health'] as const,
  list: (filters: DatasourceHealthFilters) =>
    ['datasource-health', 'list', filters] as const,
};

export async function fetchDatasourceHealth(
  filters: DatasourceHealthFilters = {},
): Promise<DatasourceHealthPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DatasourceHealthPage>(BASE, { params });
  return data;
}
