import { apiClient } from './client';
import type {
  CreateDeploymentRoutingPolicyInput,
  DeploymentRoutingPolicy,
  UpdateDeploymentRoutingPolicyInput,
} from '@/types/api';

const BASE = '/api/v1/admin/deployment-routing-policies';

export const deploymentRoutingPolicyKeys = {
  all: ['deployment-routing-policies'] as const,
  list: () => ['deployment-routing-policies', 'list'] as const,
  detail: (id: string) => ['deployment-routing-policies', 'detail', id] as const,
};

export async function listDeploymentRoutingPolicies(): Promise<DeploymentRoutingPolicy[]> {
  const { data } = await apiClient.get<DeploymentRoutingPolicy[]>(BASE);
  return data;
}

export async function getDeploymentRoutingPolicy(id: string): Promise<DeploymentRoutingPolicy> {
  const { data } = await apiClient.get<DeploymentRoutingPolicy>(`${BASE}/${id}`);
  return data;
}

export async function createDeploymentRoutingPolicy(
  input: CreateDeploymentRoutingPolicyInput,
): Promise<DeploymentRoutingPolicy> {
  const { data } = await apiClient.post<DeploymentRoutingPolicy>(BASE, input);
  return data;
}

export async function updateDeploymentRoutingPolicy(
  id: string,
  input: UpdateDeploymentRoutingPolicyInput,
): Promise<DeploymentRoutingPolicy> {
  const { data } = await apiClient.put<DeploymentRoutingPolicy>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteDeploymentRoutingPolicy(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
