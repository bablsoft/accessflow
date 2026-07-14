import { apiClient } from './client';
import type {
  AccessDecisionResult,
  AccessGrantStatus,
  AccessRequest,
  AccessRequestPage,
  AccessRevocationResult,
  PendingAccessRequestsPage,
  RequestableConnector,
  RequestableConnectorOperation,
  RequestableDatasource,
  RequestableDatasourceSchema,
  SubmitAccessRequestInput,
} from '@/types/api';

const BASE = '/api/v1/access-requests';
const ADMIN_BASE = '/api/v1/admin/access-requests';

export interface MyAccessRequestsFilters {
  page?: number;
  size?: number;
  status?: AccessGrantStatus;
}

export const accessRequestKeys = {
  all: ['access-requests'] as const,
  mine: (filters: MyAccessRequestsFilters) => ['access-requests', 'mine', filters] as const,
  datasources: () => ['access-requests', 'datasources'] as const,
  schema: (datasourceId: string) =>
    ['access-requests', 'datasources', datasourceId, 'schema'] as const,
  connectors: () => ['access-requests', 'connectors'] as const,
  connectorOperations: (connectorId: string) =>
    ['access-requests', 'connectors', connectorId, 'operations'] as const,
  queue: () => ['access-requests', 'queue'] as const,
  queueFor: (filters: { page?: number; size?: number }) =>
    ['access-requests', 'queue', filters] as const,
};

export async function submitAccessRequest(
  input: SubmitAccessRequestInput,
): Promise<AccessRequest> {
  const { data } = await apiClient.post<AccessRequest>(BASE, input);
  return data;
}

export async function listMyAccessRequests(
  filters: MyAccessRequestsFilters = {},
): Promise<AccessRequestPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.status) params.status = filters.status;
  const { data } = await apiClient.get<AccessRequestPage>(BASE, { params });
  return data;
}

export async function listRequestableDatasources(): Promise<RequestableDatasource[]> {
  const { data } = await apiClient.get<RequestableDatasource[]>(`${BASE}/datasources`);
  return data;
}

export async function getRequestableDatasourceSchema(
  datasourceId: string,
): Promise<RequestableDatasourceSchema> {
  const { data } = await apiClient.get<RequestableDatasourceSchema>(
    `${BASE}/datasources/${datasourceId}/schema`,
  );
  return data;
}

export async function listRequestableConnectors(): Promise<RequestableConnector[]> {
  const { data } = await apiClient.get<RequestableConnector[]>(`${BASE}/connectors`);
  return data;
}

export async function listRequestableConnectorOperations(
  connectorId: string,
): Promise<RequestableConnectorOperation[]> {
  const { data } = await apiClient.get<RequestableConnectorOperation[]>(
    `${BASE}/connectors/${connectorId}/operations`,
  );
  return data;
}

export async function cancelAccessRequest(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function listPendingAccessRequests(
  filters: { page?: number; size?: number } = {},
): Promise<PendingAccessRequestsPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<PendingAccessRequestsPage>(ADMIN_BASE, { params });
  return data;
}

export async function approveAccessRequest(
  id: string,
  comment?: string,
): Promise<AccessDecisionResult> {
  const { data } = await apiClient.post<AccessDecisionResult>(`${ADMIN_BASE}/${id}/approve`, {
    comment: comment ?? null,
  });
  return data;
}

export async function rejectAccessRequest(
  id: string,
  comment: string,
): Promise<AccessDecisionResult> {
  const { data } = await apiClient.post<AccessDecisionResult>(`${ADMIN_BASE}/${id}/reject`, {
    comment,
  });
  return data;
}

export async function revokeAccessGrant(
  id: string,
  comment?: string,
): Promise<AccessRevocationResult> {
  const { data } = await apiClient.post<AccessRevocationResult>(`${ADMIN_BASE}/${id}/revoke`, {
    comment: comment ?? null,
  });
  return data;
}
