import { apiClient } from './client';
import type {
  CreateDeploymentEnvironmentInput,
  CreateDeploymentPipelineInput,
  DeploymentEnvironment,
  DeploymentPipeline,
  DeploymentPipelineGroupPermission,
  DeploymentPipelinePage,
  DeploymentPipelinePermission,
  GrantDeploymentGroupPermissionInput,
  GrantDeploymentPermissionInput,
  UpdateDeploymentEnvironmentInput,
  UpdateDeploymentPermissionInput,
  UpdateDeploymentPipelineInput,
} from '@/types/api';

const BASE = '/api/v1/deployment-pipelines';

export interface DeploymentPipelineListFilters {
  page?: number;
  size?: number;
}

export const deploymentPipelineKeys = {
  all: ['deployment-pipelines'] as const,
  lists: () => ['deployment-pipelines', 'list'] as const,
  list: (filters: DeploymentPipelineListFilters) =>
    ['deployment-pipelines', 'list', filters] as const,
  details: () => ['deployment-pipelines', 'detail'] as const,
  detail: (id: string) => ['deployment-pipelines', 'detail', id] as const,
  environments: (id: string) => ['deployment-pipelines', 'detail', id, 'environments'] as const,
  permissions: (id: string) => ['deployment-pipelines', 'detail', id, 'permissions'] as const,
  groupPermissions: (id: string) =>
    ['deployment-pipelines', 'detail', id, 'permissions', 'groups'] as const,
};

export async function listDeploymentPipelines(
  filters: DeploymentPipelineListFilters = {},
): Promise<DeploymentPipelinePage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DeploymentPipelinePage>(BASE, { params });
  return data;
}

export async function getDeploymentPipeline(id: string): Promise<DeploymentPipeline> {
  const { data } = await apiClient.get<DeploymentPipeline>(`${BASE}/${id}`);
  return data;
}

export async function createDeploymentPipeline(
  input: CreateDeploymentPipelineInput,
): Promise<DeploymentPipeline> {
  const { data } = await apiClient.post<DeploymentPipeline>(BASE, input);
  return data;
}

export async function updateDeploymentPipeline(
  id: string,
  input: UpdateDeploymentPipelineInput,
): Promise<DeploymentPipeline> {
  const { data } = await apiClient.put<DeploymentPipeline>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteDeploymentPipeline(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function listDeploymentEnvironments(
  pipelineId: string,
): Promise<DeploymentEnvironment[]> {
  const { data } = await apiClient.get<DeploymentEnvironment[]>(
    `${BASE}/${pipelineId}/environments`,
  );
  return data;
}

export async function createDeploymentEnvironment(
  pipelineId: string,
  input: CreateDeploymentEnvironmentInput,
): Promise<DeploymentEnvironment> {
  const { data } = await apiClient.post<DeploymentEnvironment>(
    `${BASE}/${pipelineId}/environments`,
    input,
  );
  return data;
}

export async function updateDeploymentEnvironment(
  pipelineId: string,
  environmentId: string,
  input: UpdateDeploymentEnvironmentInput,
): Promise<DeploymentEnvironment> {
  const { data } = await apiClient.put<DeploymentEnvironment>(
    `${BASE}/${pipelineId}/environments/${environmentId}`,
    input,
  );
  return data;
}

export async function deleteDeploymentEnvironment(
  pipelineId: string,
  environmentId: string,
): Promise<void> {
  await apiClient.delete(`${BASE}/${pipelineId}/environments/${environmentId}`);
}

export async function listDeploymentPermissions(
  pipelineId: string,
): Promise<DeploymentPipelinePermission[]> {
  const { data } = await apiClient.get<DeploymentPipelinePermission[]>(
    `${BASE}/${pipelineId}/permissions`,
  );
  return data;
}

export async function grantDeploymentPermission(
  pipelineId: string,
  input: GrantDeploymentPermissionInput,
): Promise<DeploymentPipelinePermission> {
  const { data } = await apiClient.post<DeploymentPipelinePermission>(
    `${BASE}/${pipelineId}/permissions`,
    input,
  );
  return data;
}

export async function updateDeploymentPermission(
  pipelineId: string,
  permissionId: string,
  input: UpdateDeploymentPermissionInput,
): Promise<DeploymentPipelinePermission> {
  const { data } = await apiClient.put<DeploymentPipelinePermission>(
    `${BASE}/${pipelineId}/permissions/${permissionId}`,
    input,
  );
  return data;
}

export async function revokeDeploymentPermission(
  pipelineId: string,
  permissionId: string,
): Promise<void> {
  await apiClient.delete(`${BASE}/${pipelineId}/permissions/${permissionId}`);
}

export async function listDeploymentGroupPermissions(
  pipelineId: string,
): Promise<DeploymentPipelineGroupPermission[]> {
  const { data } = await apiClient.get<DeploymentPipelineGroupPermission[]>(
    `${BASE}/${pipelineId}/permissions/groups`,
  );
  return data;
}

export async function grantDeploymentGroupPermission(
  pipelineId: string,
  input: GrantDeploymentGroupPermissionInput,
): Promise<DeploymentPipelineGroupPermission> {
  const { data } = await apiClient.post<DeploymentPipelineGroupPermission>(
    `${BASE}/${pipelineId}/permissions/groups`,
    input,
  );
  return data;
}

export async function updateDeploymentGroupPermission(
  pipelineId: string,
  permissionId: string,
  input: UpdateDeploymentPermissionInput,
): Promise<DeploymentPipelineGroupPermission> {
  const { data } = await apiClient.put<DeploymentPipelineGroupPermission>(
    `${BASE}/${pipelineId}/permissions/groups/${permissionId}`,
    input,
  );
  return data;
}

export async function revokeDeploymentGroupPermission(
  pipelineId: string,
  permissionId: string,
): Promise<void> {
  await apiClient.delete(`${BASE}/${pipelineId}/permissions/groups/${permissionId}`);
}
