import { apiClient } from './client';
import type {
  AccessSimulationRequest,
  AccessSimulationResult,
  EffectiveAccessPage,
  StatementCapability,
} from '@/types/api';

export interface EffectiveAccessFilters {
  datasource_id: string;
  table: string;
  capability: StatementCapability;
  page?: number;
  size?: number;
}

/** Keys for the reverse index only — a simulation is a POST and is never cached (see below). */
export const effectiveAccessKeys = {
  all: ['effective-access'] as const,
  lists: () => ['effective-access', 'list'] as const,
  list: (filters: EffectiveAccessFilters) => ['effective-access', 'list', filters] as const,
};

/**
 * Query decision trace (#859): replays one hypothetical query through the stages the live pipeline
 * runs and writes nothing. Callers use `useMutation` — a trace must never run on its own, and there
 * is deliberately no query-key factory for it.
 */
export async function simulateAccess(
  payload: AccessSimulationRequest,
): Promise<AccessSimulationResult> {
  const { data } = await apiClient.post<AccessSimulationResult>(
    '/api/v1/admin/access-simulations',
    payload,
  );
  return data;
}

/** Reverse access index (#859): every identity that can perform `capability` on `table`. */
export async function getEffectiveAccess(
  filters: EffectiveAccessFilters,
): Promise<EffectiveAccessPage> {
  const { data } = await apiClient.get<EffectiveAccessPage>('/api/v1/admin/effective-access', {
    params: filters,
  });
  return data;
}
