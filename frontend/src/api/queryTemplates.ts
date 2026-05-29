import { apiClient } from './client';
import type {
  CreateQueryTemplateInput,
  QueryTemplate,
  QueryTemplateFilters,
  QueryTemplatePage,
  UpdateQueryTemplateInput,
} from '@/types/api';

const BASE = '/api/v1/query-templates';

export const queryTemplateKeys = {
  all: ['queryTemplates'] as const,
  lists: () => ['queryTemplates', 'list'] as const,
  list: (filters: QueryTemplateFilters = {}) => ['queryTemplates', 'list', filters] as const,
  detail: (id: string) => ['queryTemplates', 'detail', id] as const,
};

export async function listQueryTemplates(
  filters: QueryTemplateFilters = {},
): Promise<QueryTemplatePage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.datasourceId) params.datasourceId = filters.datasourceId;
  if (filters.tag) params.tag = filters.tag;
  if (filters.visibility) params.visibility = filters.visibility;
  if (filters.q) params.q = filters.q;
  const { data } = await apiClient.get<QueryTemplatePage>(BASE, { params });
  return data;
}

export async function getQueryTemplate(id: string): Promise<QueryTemplate> {
  const { data } = await apiClient.get<QueryTemplate>(`${BASE}/${id}`);
  return data;
}

export async function createQueryTemplate(
  input: CreateQueryTemplateInput,
): Promise<QueryTemplate> {
  const { data } = await apiClient.post<QueryTemplate>(BASE, input);
  return data;
}

export async function updateQueryTemplate(
  id: string,
  input: UpdateQueryTemplateInput,
): Promise<QueryTemplate> {
  const { data } = await apiClient.put<QueryTemplate>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteQueryTemplate(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
