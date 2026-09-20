import { apiClient } from './client';
import type {
  CreateServiceAccountInput,
  GrantDelegatedPrincipalInput,
  IssueServiceAccountKeyInput,
  IssuedServiceAccountKey,
  McpToolCatalog,
  RotateServiceAccountKeyInput,
  RotatedServiceAccountKey,
  ServiceAccount,
  ServiceAccountDelegation,
  ServiceAccountListFilters,
  ServiceAccountPage,
  UpdateServiceAccountInput,
} from '@/types/api';

const BASE = '/api/v1/admin/service-accounts';

export const serviceAccountKeys = {
  all: ['service-accounts'] as const,
  lists: () => ['service-accounts', 'list'] as const,
  list: (filters: ServiceAccountListFilters) => ['service-accounts', 'list', filters] as const,
  details: () => ['service-accounts', 'detail'] as const,
  detail: (id: string) => ['service-accounts', 'detail', id] as const,
  delegations: (id: string) => ['service-accounts', 'detail', id, 'delegations'] as const,
  mcpTools: () => ['service-accounts', 'mcp-tools'] as const,
};

export async function listServiceAccounts(
  filters: ServiceAccountListFilters = {},
): Promise<ServiceAccountPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.managed_by) params.managed_by = filters.managed_by;
  const { data } = await apiClient.get<ServiceAccountPage>(BASE, { params });
  return data;
}

export async function getServiceAccount(id: string): Promise<ServiceAccount> {
  const { data } = await apiClient.get<ServiceAccount>(`${BASE}/${id}`);
  return data;
}

export async function createServiceAccount(
  input: CreateServiceAccountInput,
): Promise<ServiceAccount> {
  const { data } = await apiClient.post<ServiceAccount>(BASE, input);
  return data;
}

export async function updateServiceAccount(
  id: string,
  input: UpdateServiceAccountInput,
): Promise<ServiceAccount> {
  const { data } = await apiClient.put<ServiceAccount>(`${BASE}/${id}`, input);
  return data;
}

export async function deactivateServiceAccount(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function issueServiceAccountKey(
  id: string,
  input: IssueServiceAccountKeyInput,
): Promise<IssuedServiceAccountKey> {
  const { data } = await apiClient.post<IssuedServiceAccountKey>(`${BASE}/${id}/api-keys`, input);
  return data;
}

export async function rotateServiceAccountKey(
  id: string,
  keyId: string,
  input: RotateServiceAccountKeyInput,
): Promise<RotatedServiceAccountKey> {
  const { data } = await apiClient.post<RotatedServiceAccountKey>(
    `${BASE}/${id}/api-keys/${keyId}/rotate`,
    input,
  );
  return data;
}

export async function revokeServiceAccountKey(id: string, keyId: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}/api-keys/${keyId}`);
}

export async function listDelegatedPrincipals(id: string): Promise<ServiceAccountDelegation[]> {
  const { data } = await apiClient.get<ServiceAccountDelegation[]>(
    `${BASE}/${id}/delegated-principals`,
  );
  return data;
}

export async function grantDelegatedPrincipal(
  id: string,
  input: GrantDelegatedPrincipalInput,
): Promise<ServiceAccountDelegation> {
  const { data } = await apiClient.post<ServiceAccountDelegation>(
    `${BASE}/${id}/delegated-principals`,
    input,
  );
  return data;
}

export async function revokeDelegatedPrincipal(id: string, delegationId: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}/delegated-principals/${delegationId}`);
}

/** The MCP tool names an allow-list may reference, in the order the server advertises them. */
export async function listMcpTools(): Promise<string[]> {
  const { data } = await apiClient.get<McpToolCatalog>(`${BASE}/mcp-tools`);
  return data.tools;
}
