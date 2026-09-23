import { apiClient } from './client';
import type { ApiCallSimulationRequest, ApiCallSimulationResult } from '@/types/api';

/**
 * API-call decision trace (#967): replays one hypothetical call through the governance pipeline
 * without contacting the governed API. A POST used via `useMutation`; never cached.
 */
export async function simulateApiCall(
  payload: ApiCallSimulationRequest,
): Promise<ApiCallSimulationResult> {
  const { data } = await apiClient.post<ApiCallSimulationResult>(
    '/api/v1/admin/api-call-simulations',
    payload,
  );
  return data;
}
