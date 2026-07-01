import { apiClient } from './client';
import type {
  ConnectionTestResult,
  CreateDatasourceInput,
  CreateGroupPermissionInput,
  CreatePermissionInput,
  Datasource,
  DatasourceGroupPermission,
  DatasourcePage,
  DatasourcePermission,
  DatasourceSchema,
  DatasourceTypesResponse,
  SampleRowsResponse,
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
  sampleRows: (id: string, schema: string | undefined, table: string, limit: number) =>
    ['datasources', 'detail', id, 'sample-rows', schema ?? '', table, limit] as const,
  permissions: (id: string) => ['datasources', 'detail', id, 'permissions'] as const,
  groupPermissions: (id: string) =>
    ['datasources', 'detail', id, 'permissions', 'groups'] as const,
  types: () => ['datasources', 'types'] as const,
};

export interface SampleRowsParams {
  schema?: string;
  table: string;
  limit?: number;
}

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

export interface TestReplicaInput {
  jdbc_url: string;
  username: string;
  password?: string;
}

export async function testReplicaConnection(
  id: string,
  input: TestReplicaInput,
): Promise<ConnectionTestResult> {
  const { data } = await apiClient.post<ConnectionTestResult>(
    `${BASE}/${id}/test-replica`,
    input,
  );
  return data;
}

export async function getDatasourceSchema(id: string): Promise<DatasourceSchema> {
  const { data } = await apiClient.get<DatasourceSchema>(`${BASE}/${id}/schema`);
  return data;
}

export async function getDatasourceSampleRows(
  id: string,
  params: SampleRowsParams,
): Promise<SampleRowsResponse> {
  const query: Record<string, string | number> = { table: params.table };
  if (params.schema) query.schema = params.schema;
  if (typeof params.limit === 'number') query.limit = params.limit;
  const { data } = await apiClient.get<SampleRowsResponse>(`${BASE}/${id}/sample-rows`, {
    params: query,
  });
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

export async function listGroupPermissions(
  id: string,
): Promise<DatasourceGroupPermission[]> {
  const { data } = await apiClient.get<{ content: DatasourceGroupPermission[] }>(
    `${BASE}/${id}/permissions/groups`,
  );
  return data.content;
}

export async function grantGroupPermission(
  id: string,
  input: CreateGroupPermissionInput,
): Promise<DatasourceGroupPermission> {
  const { data } = await apiClient.post<DatasourceGroupPermission>(
    `${BASE}/${id}/permissions/groups`,
    input,
  );
  return data;
}

export async function revokeGroupPermission(id: string, permId: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}/permissions/groups/${permId}`);
}

export async function getDatasourceTypes(): Promise<DatasourceTypesResponse> {
  const { data } = await apiClient.get<DatasourceTypesResponse>(`${BASE}/types`);
  return data;
}
