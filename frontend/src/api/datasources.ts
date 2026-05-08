import { apiClient } from './client';
import type {
  ConnectionTestResult,
  CreateDatasourceInput,
  CreatePermissionInput,
  Datasource,
  DatasourcePage,
  DatasourcePermission,
  DatasourceSchema,
  DatasourceTypesResponse,
  UpdateDatasourceInput,
} from '@/types/api';

const BASE = '/api/v1/datasources';

export interface DatasourceListFilters {
  page?: number;
  size?: number;
}

export const datasourceKeys = {
  all: ['datasources'] as const,
  lists: () => ['datasources', 'list'] as const,
  list: (filters: DatasourceListFilters) => ['datasources', 'list', filters] as const,
  details: () => ['datasources', 'detail'] as const,
  detail: (id: string) => ['datasources', 'detail', id] as const,
  schema: (id: string) => ['datasources', 'detail', id, 'schema'] as const,
  permissions: (id: string) => ['datasources', 'detail', id, 'permissions'] as const,
  types: () => ['datasources', 'types'] as const,
};

export async function listDatasources(
  filters: DatasourceListFilters = {},
): Promise<DatasourcePage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DatasourcePage>(BASE, { params });
  return data;
}

export async function getDatasource(id: string): Promise<Datasource> {
  const { data } = await apiClient.get<Datasource>(`${BASE}/${id}`);
  return data;
}

export async function createDatasource(input: CreateDatasourceInput): Promise<Datasource> {
  const { data } = await apiClient.post<Datasource>(BASE, input);
  return data;
}

export async function updateDatasource(
  id: string,
  input: UpdateDatasourceInput,
): Promise<Datasource> {
  const { data } = await apiClient.put<Datasource>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteDatasource(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function testConnection(id: string): Promise<ConnectionTestResult> {
  const { data } = await apiClient.post<ConnectionTestResult>(`${BASE}/${id}/test`);
  return data;
}

export async function getDatasourceSchema(id: string): Promise<DatasourceSchema> {
  const { data } = await apiClient.get<DatasourceSchema>(`${BASE}/${id}/schema`);
  return data;
}

export async function listPermissions(id: string): Promise<DatasourcePermission[]> {
  const { data } = await apiClient.get<{ content: DatasourcePermission[] }>(
    `${BASE}/${id}/permissions`,
  );
  return data.content;
}

export async function grantPermission(
  id: string,
  input: CreatePermissionInput,
): Promise<DatasourcePermission> {
  const { data } = await apiClient.post<DatasourcePermission>(
    `${BASE}/${id}/permissions`,
    input,
  );
  return data;
}

export async function revokePermission(id: string, permId: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}/permissions/${permId}`);
}

export async function getDatasourceTypes(): Promise<DatasourceTypesResponse> {
  const { data } = await apiClient.get<DatasourceTypesResponse>(`${BASE}/types`);
  return data;
}
