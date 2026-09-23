import { apiClient } from './client';
import type { DeploymentSimulationRequest, DeploymentSimulationResult } from '@/types/api';

/**
 * Deployment decision trace (#967): walks the trigger grant, freeze window, routing, environment
 * policy and the gate's own `releasable` function at an optional hypothetical instant `at`. A POST
 * used via `useMutation`; never cached.
 */
export async function simulateDeployment(
  payload: DeploymentSimulationRequest,
): Promise<DeploymentSimulationResult> {
  const { data } = await apiClient.post<DeploymentSimulationResult>(
    '/api/v1/admin/deployment-simulations',
    payload,
  );
  return data;
}
