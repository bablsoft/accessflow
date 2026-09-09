import { apiClient } from './client';
import type {
  MaskingSimulationRequest,
  MaskingSimulationResponse,
  RoutingSimulationRequest,
  RoutingSimulationResponse,
  RowSecuritySimulationRequest,
  RowSecuritySimulationResponse,
} from '@/types/api';

/**
 * Policy simulator (AF-630) — one module for all three policy kinds, because their responses share
 * an envelope (counts, per-user impact, capped samples, caveats) and the UI renders them the same
 * way. Each call is a POST that takes a draft, so callers use `useMutation`, not `useQuery`: a
 * simulation must never run on its own.
 */
export const policySimulationKeys = {
  all: ['policySimulation'] as const,
  routing: () => ['policySimulation', 'routing'] as const,
  rowSecurity: (datasourceId: string) =>
    ['policySimulation', 'rowSecurity', datasourceId] as const,
  masking: (datasourceId: string) => ['policySimulation', 'masking', datasourceId] as const,
};

export async function simulateRoutingPolicy(
  payload: RoutingSimulationRequest,
): Promise<RoutingSimulationResponse> {
  const { data } = await apiClient.post<RoutingSimulationResponse>(
    '/api/v1/admin/routing-policies/simulate',
    payload,
  );
  return data;
}

export async function simulateRowSecurityPolicy(
  datasourceId: string,
  payload: RowSecuritySimulationRequest,
): Promise<RowSecuritySimulationResponse> {
  const { data } = await apiClient.post<RowSecuritySimulationResponse>(
    `/api/v1/datasources/${datasourceId}/row-security-policies/simulate`,
    payload,
  );
  return data;
}

export async function simulateMaskingPolicy(
  datasourceId: string,
  payload: MaskingSimulationRequest,
): Promise<MaskingSimulationResponse> {
  const { data } = await apiClient.post<MaskingSimulationResponse>(
    `/api/v1/datasources/${datasourceId}/masking-policies/simulate`,
    payload,
  );
  return data;
}
