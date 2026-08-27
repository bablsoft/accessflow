import { apiClient } from './client';
import type {
  DeploymentGateStatus,
  DeploymentRequest,
  DeploymentRequestListFilters,
  DeploymentRequestPage,
} from '@/types/api';

const BASE = '/api/v1/deployment-requests';

export const deploymentKeys = {
  all: ['deployments'] as const,
  lists: () => ['deployments', 'list'] as const,
  list: (filters: DeploymentRequestListFilters) => ['deployments', 'list', filters] as const,
  details: () => ['deployments', 'detail'] as const,
  detail: (id: string) => ['deployments', 'detail', id] as const,
  gate: (id: string) => ['deployments', 'detail', id, 'gate'] as const,
};

export async function listDeploymentRequests(
  filters: DeploymentRequestListFilters = {},
): Promise<DeploymentRequestPage> {
  const params: Record<string, string | number> = {};
  if (filters.status) params.status = filters.status;
  if (filters.pipeline_id) params.pipeline_id = filters.pipeline_id;
  if (filters.environment) params.environment = filters.environment;
  if (filters.version) params.version = filters.version;
  if (filters.submitted_by) params.submitted_by = filters.submitted_by;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DeploymentRequestPage>(BASE, { params });
  return data;
}

export async function getDeploymentRequest(id: string): Promise<DeploymentRequest> {
  const { data } = await apiClient.get<DeploymentRequest>(`${BASE}/${id}`);
  return data;
}

export async function cancelDeploymentRequest(id: string): Promise<void> {
  await apiClient.post(`${BASE}/${id}/cancel`);
}

export async function getDeploymentGate(requestId: string): Promise<DeploymentGateStatus> {
  const { data } = await apiClient.get<DeploymentGateStatus>('/api/v1/deployment-gate', {
    params: { request_id: requestId },
  });
  return data;
}
