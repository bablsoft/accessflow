import { apiClient } from './client';
import type {
  DecisionHook,
  DecisionHookTestResult,
  DecisionHookWriteRequest,
} from '@/types/api';

const BASE = '/api/v1/admin/decision-hooks';

export const decisionHookKeys = {
  all: ['decisionHooks'] as const,
  lists: () => ['decisionHooks', 'list'] as const,
};

export async function listDecisionHooks(): Promise<DecisionHook[]> {
  const { data } = await apiClient.get<DecisionHook[]>(BASE);
  return data;
}

export async function createDecisionHook(payload: DecisionHookWriteRequest): Promise<DecisionHook> {
  const { data } = await apiClient.post<DecisionHook>(BASE, payload);
  return data;
}

export async function updateDecisionHook(
  id: string,
  payload: DecisionHookWriteRequest,
): Promise<DecisionHook> {
  const { data } = await apiClient.put<DecisionHook>(`${BASE}/${id}`, payload);
  return data;
}

export async function deleteDecisionHook(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function testDecisionHook(id: string): Promise<DecisionHookTestResult> {
  const { data } = await apiClient.post<DecisionHookTestResult>(`${BASE}/${id}/test`);
  return data;
}
