import { apiClient } from './client';
import type {
  BreakGlassEvent,
  BreakGlassEventPage,
  BreakGlassListFilters,
} from '@/types/api';

const ADMIN_BASE = '/api/v1/admin/break-glass';

export const breakGlassKeys = {
  all: ['break-glass'] as const,
  lists: () => ['break-glass', 'list'] as const,
  list: (filters: BreakGlassListFilters) => ['break-glass', 'list', filters] as const,
  detail: (id: string) => ['break-glass', 'detail', id] as const,
};

export async function listBreakGlassEvents(
  filters: BreakGlassListFilters = {},
): Promise<BreakGlassEventPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.status) params.status = filters.status;
  if (filters.datasource_id) params.datasourceId = filters.datasource_id;
  if (filters.user_id) params.userId = filters.user_id;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  const { data } = await apiClient.get<BreakGlassEventPage>(ADMIN_BASE, { params });
  return data;
}

export async function acknowledgeBreakGlassEvent(
  id: string,
  comment?: string,
): Promise<BreakGlassEvent> {
  const { data } = await apiClient.post<BreakGlassEvent>(
    `${ADMIN_BASE}/${id}/acknowledge`,
    comment ? { comment } : {},
  );
  return data;
}
