import { apiClient } from './client';
import type {
  ApiConnectionTestResult,
  ApiConnector,
  ApiConnectorGroupPermission,
  ApiConnectorHealth,
  ApiConnectorPage,
  ApiConnectorPermission,
  ApiDriftFinding,
  ApiDriftFindingStatus,
  ApiOperation,
  ApiOperationFilter,
  ApiSchema,
  ApiSchemaFilterPreview,
  ApiSchemaRefreshResult,
  ApiSchemaRevision,
  CreateApiConnectorInput,
  GrantApiConnectorGroupPermissionInput,
  GrantApiConnectorPermissionInput,
  UpdateApiConnectorInput,
  UpdateApiConnectorGroupPermissionInput,
  UpdateApiConnectorPermissionInput,
  UploadApiSchemaInput,
} from '@/types/api';

const BASE = '/api/v1/api-connectors';

export interface ApiConnectorListFilters {
  page?: number;
  size?: number;
}

export const apiConnectorKeys = {
  all: ['api-connectors'] as const,
  lists: () => ['api-connectors', 'list'] as const,
  list: (filters: ApiConnectorListFilters) => ['api-connectors', 'list', filters] as const,
  details: () => ['api-connectors', 'detail'] as const,
  detail: (id: string) => ['api-connectors', 'detail', id] as const,
  schemas: (id: string) => ['api-connectors', 'detail', id, 'schemas'] as const,
  operations: (id: string) => ['api-connectors', 'detail', id, 'operations'] as const,
  permissions: (id: string) => ['api-connectors', 'detail', id, 'permissions'] as const,
  groupPermissions: (id: string) =>
    ['api-connectors', 'detail', id, 'permissions', 'groups'] as const,
  health: (id: string) => ['api-connectors', 'detail', id, 'health'] as const,
  revisions: (id: string, schemaId: string) =>
    ['api-connectors', 'detail', id, 'schemas', schemaId, 'revisions'] as const,
  /** Without a status, the prefix of every findings query for the connector. */
  findings: (id: string, status?: ApiDriftFindingStatus) =>
    status
      ? (['api-connectors', 'detail', id, 'drift-findings', status] as const)
      : (['api-connectors', 'detail', id, 'drift-findings'] as const),
};

export async function listApiConnectors(
  filters: ApiConnectorListFilters = {},
): Promise<ApiConnectorPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<ApiConnectorPage>(BASE, { params });
  return data;
}

export async function getApiConnector(id: string): Promise<ApiConnector> {
  const { data } = await apiClient.get<ApiConnector>(`${BASE}/${id}`);
  return data;
}

export async function createApiConnector(input: CreateApiConnectorInput): Promise<ApiConnector> {
  const { data } = await apiClient.post<ApiConnector>(BASE, input);
  return data;
}

export async function updateApiConnector(
  id: string,
  input: UpdateApiConnectorInput,
): Promise<ApiConnector> {
  const { data } = await apiClient.put<ApiConnector>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteApiConnector(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function testApiConnector(id: string): Promise<ApiConnectionTestResult> {
  const { data } = await apiClient.post<ApiConnectionTestResult>(`${BASE}/${id}/test`);
  return data;
}

export async function listApiSchemas(connectorId: string): Promise<ApiSchema[]> {
  const { data } = await apiClient.get<ApiSchema[]>(`${BASE}/${connectorId}/schemas`);
  return data;
}

export async function uploadApiSchema(
  connectorId: string,
  input: UploadApiSchemaInput,
): Promise<ApiSchema> {
  const { data } = await apiClient.post<ApiSchema>(`${BASE}/${connectorId}/schemas`, input);
  return data;
}

export async function previewApiSchemaFilter(
  connectorId: string,
  input: UploadApiSchemaInput,
): Promise<ApiSchemaFilterPreview> {
  const { data } = await apiClient.post<ApiSchemaFilterPreview>(
    `${BASE}/${connectorId}/schemas/preview`,
    input,
  );
  return data;
}

export async function updateApiSchemaFilter(
  connectorId: string,
  schemaId: string,
  filter: ApiOperationFilter,
): Promise<ApiSchema> {
  const { data } = await apiClient.put<ApiSchema>(
    `${BASE}/${connectorId}/schemas/${schemaId}/filter`,
    filter,
  );
  return data;
}

export async function deleteApiSchema(connectorId: string, schemaId: string): Promise<void> {
  await apiClient.delete(`${BASE}/${connectorId}/schemas/${schemaId}`);
}

export async function listApiOperations(connectorId: string): Promise<ApiOperation[]> {
  const { data } = await apiClient.get<ApiOperation[]>(`${BASE}/${connectorId}/operations`);
  return data;
}

export async function getApiConnectorHealth(connectorId: string): Promise<ApiConnectorHealth> {
  const { data } = await apiClient.get<ApiConnectorHealth>(`${BASE}/${connectorId}/health`);
  return data;
}

export async function listApiSchemaRevisions(
  connectorId: string,
  schemaId: string,
): Promise<ApiSchemaRevision[]> {
  const { data } = await apiClient.get<ApiSchemaRevision[]>(
    `${BASE}/${connectorId}/schemas/${schemaId}/revisions`,
  );
  return data;
}

export async function listApiDriftFindings(
  connectorId: string,
  status?: ApiDriftFindingStatus,
): Promise<ApiDriftFinding[]> {
  const { data } = await apiClient.get<ApiDriftFinding[]>(`${BASE}/${connectorId}/drift-findings`, {
    params: status ? { status } : {},
  });
  return data;
}

export async function refreshApiSchema(
  connectorId: string,
  schemaId: string,
): Promise<ApiSchemaRefreshResult> {
  const { data } = await apiClient.post<ApiSchemaRefreshResult>(
    `${BASE}/${connectorId}/schemas/${schemaId}/refresh`,
  );
  return data;
}

export async function updateApiSchemaRefreshPolicy(
  connectorId: string,
  schemaId: string,
  refreshIntervalMinutes: number | null,
): Promise<ApiSchema> {
  const { data } = await apiClient.put<ApiSchema>(
    `${BASE}/${connectorId}/schemas/${schemaId}/refresh-policy`,
    { refresh_interval_minutes: refreshIntervalMinutes },
  );
  return data;
}

export async function acceptApiSchemaRevision(
  connectorId: string,
  schemaId: string,
  revisionId: string,
): Promise<ApiSchema> {
  const { data } = await apiClient.post<ApiSchema>(
    `${BASE}/${connectorId}/schemas/${schemaId}/revisions/${revisionId}/accept`,
  );
  return data;
}

export async function dismissApiDriftFinding(
  connectorId: string,
  findingId: string,
): Promise<void> {
  await apiClient.post(`${BASE}/${connectorId}/drift-findings/${findingId}/dismiss`);
}

export async function listApiConnectorPermissions(
  connectorId: string,
): Promise<ApiConnectorPermission[]> {
  const { data } = await apiClient.get<ApiConnectorPermission[]>(
    `${BASE}/${connectorId}/permissions`,
  );
  return data;
}

export async function grantApiConnectorPermission(
  connectorId: string,
  input: GrantApiConnectorPermissionInput,
): Promise<ApiConnectorPermission> {
  const { data } = await apiClient.post<ApiConnectorPermission>(
    `${BASE}/${connectorId}/permissions`,
    input,
  );
  return data;
}

export async function updateApiConnectorPermission(
  connectorId: string,
  permissionId: string,
  input: UpdateApiConnectorPermissionInput,
): Promise<ApiConnectorPermission> {
  const { data } = await apiClient.put<ApiConnectorPermission>(
    `${BASE}/${connectorId}/permissions/${permissionId}`,
    input,
  );
  return data;
}

export async function revokeApiConnectorPermission(
  connectorId: string,
  permissionId: string,
): Promise<void> {
  await apiClient.delete(`${BASE}/${connectorId}/permissions/${permissionId}`);
}

export async function listApiConnectorGroupPermissions(
  connectorId: string,
): Promise<ApiConnectorGroupPermission[]> {
  const { data } = await apiClient.get<ApiConnectorGroupPermission[]>(
    `${BASE}/${connectorId}/permissions/groups`,
  );
  return data;
}

export async function grantApiConnectorGroupPermission(
  connectorId: string,
  input: GrantApiConnectorGroupPermissionInput,
): Promise<ApiConnectorGroupPermission> {
  const { data } = await apiClient.post<ApiConnectorGroupPermission>(
    `${BASE}/${connectorId}/permissions/groups`,
    input,
  );
  return data;
}

export async function updateApiConnectorGroupPermission(
  connectorId: string,
  permissionId: string,
  input: UpdateApiConnectorGroupPermissionInput,
): Promise<ApiConnectorGroupPermission> {
  const { data } = await apiClient.put<ApiConnectorGroupPermission>(
    `${BASE}/${connectorId}/permissions/groups/${permissionId}`,
    input,
  );
  return data;
}

export async function revokeApiConnectorGroupPermission(
  connectorId: string,
  permissionId: string,
): Promise<void> {
  await apiClient.delete(`${BASE}/${connectorId}/permissions/groups/${permissionId}`);
}
