import { apiClient } from './client';
import type { QuerySuggestion, QuerySuggestionList } from '@/types/api';

const base = (datasourceId: string) => `/api/v1/datasources/${datasourceId}/query-suggestions`;

export const querySuggestionKeys = {
  all: ['query-suggestions'] as const,
  list: (datasourceId: string, limit?: number) =>
    ['query-suggestions', 'list', datasourceId, limit ?? null] as const,
};

/**
 * Draft queries mined from the organisation's own approved history (#776). The backend has already
 * filtered these to what the caller may run, so the panel renders whatever comes back.
 */
export async function fetchQuerySuggestions(
  datasourceId: string,
  limit?: number,
): Promise<QuerySuggestion[]> {
  const params: Record<string, number> = {};
  if (limit !== undefined) {
    params.limit = limit;
  }
  const { data } = await apiClient.get<QuerySuggestionList>(base(datasourceId), { params });
  return data.suggestions;
}
