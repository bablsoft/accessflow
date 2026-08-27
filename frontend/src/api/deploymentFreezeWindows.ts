import { apiClient } from './client';
import type {
  DeploymentFreezeWindow,
  DeploymentFreezeWindowInput,
  DeploymentFreezeWindowPage,
} from '@/types/api';

const BASE = '/api/v1/deployment-freeze-windows';

export interface DeploymentFreezeWindowListFilters {
  page?: number;
  size?: number;
}

export const deploymentFreezeWindowKeys = {
  all: ['deployment-freeze-windows'] as const,
  lists: () => ['deployment-freeze-windows', 'list'] as const,
  list: (filters: DeploymentFreezeWindowListFilters) =>
    ['deployment-freeze-windows', 'list', filters] as const,
  detail: (id: string) => ['deployment-freeze-windows', 'detail', id] as const,
};

export async function listDeploymentFreezeWindows(
  filters: DeploymentFreezeWindowListFilters = {},
): Promise<DeploymentFreezeWindowPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DeploymentFreezeWindowPage>(BASE, { params });
  return data;
}

export async function getDeploymentFreezeWindow(id: string): Promise<DeploymentFreezeWindow> {
  const { data } = await apiClient.get<DeploymentFreezeWindow>(`${BASE}/${id}`);
  return data;
}

export async function createDeploymentFreezeWindow(
  input: DeploymentFreezeWindowInput,
): Promise<DeploymentFreezeWindow> {
  const { data } = await apiClient.post<DeploymentFreezeWindow>(BASE, input);
  return data;
}

export async function updateDeploymentFreezeWindow(
  id: string,
  input: DeploymentFreezeWindowInput,
): Promise<DeploymentFreezeWindow> {
  const { data } = await apiClient.put<DeploymentFreezeWindow>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteDeploymentFreezeWindow(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
