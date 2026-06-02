import { apiClient } from './client';
import type {
  RoutingPolicy,
  RoutingPolicyWriteRequest,
} from '@/types/api';

const BASE = '/api/v1/admin/routing-policies';

export const routingPolicyKeys = {
  all: ['routingPolicies'] as const,
  lists: () => ['routingPolicies', 'list'] as const,
  detail: (id: string) => ['routingPolicies', 'detail', id] as const,
};

export async function listRoutingPolicies(): Promise<RoutingPolicy[]> {
  const { data } = await apiClient.get<RoutingPolicy[]>(BASE);
  return data;
}

export async function getRoutingPolicy(id: string): Promise<RoutingPolicy> {
  const { data } = await apiClient.get<RoutingPolicy>(`${BASE}/${id}`);
  return data;
}

export async function createRoutingPolicy(
  payload: RoutingPolicyWriteRequest,
): Promise<RoutingPolicy> {
  const { data } = await apiClient.post<RoutingPolicy>(BASE, payload);
  return data;
}

export async function updateRoutingPolicy(
  id: string,
  payload: RoutingPolicyWriteRequest,
): Promise<RoutingPolicy> {
  const { data } = await apiClient.put<RoutingPolicy>(`${BASE}/${id}`, payload);
  return data;
}

export async function deleteRoutingPolicy(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function reorderRoutingPolicies(
  orderedIds: string[],
): Promise<RoutingPolicy[]> {
  const { data } = await apiClient.put<RoutingPolicy[]>(`${BASE}/reorder`, {
    ordered_ids: orderedIds,
  });
  return data;
}
