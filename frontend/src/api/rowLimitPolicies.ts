import { apiClient } from './client';
import type {
  CreateRowLimitPolicyInput,
  RowLimitPolicy,
  UpdateRowLimitPolicyInput,
} from '@/types/api';

const base = (datasourceId: string) =>
  `/api/v1/datasources/${datasourceId}/row-limit-policies`;

export const rowLimitPolicyKeys = {
  all: ['row-limit-policies'] as const,
  list: (datasourceId: string) => ['row-limit-policies', 'list', datasourceId] as const,
};

export async function listRowLimitPolicies(
  datasourceId: string,
): Promise<RowLimitPolicy[]> {
  const { data } = await apiClient.get<{ content: RowLimitPolicy[] }>(base(datasourceId));
  return data.content;
}

export async function createRowLimitPolicy(
  datasourceId: string,
  input: CreateRowLimitPolicyInput,
): Promise<RowLimitPolicy> {
  const { data } = await apiClient.post<RowLimitPolicy>(base(datasourceId), input);
  return data;
}

export async function updateRowLimitPolicy(
  datasourceId: string,
  policyId: string,
  input: UpdateRowLimitPolicyInput,
): Promise<RowLimitPolicy> {
  const { data } = await apiClient.put<RowLimitPolicy>(
    `${base(datasourceId)}/${policyId}`,
    input,
  );
  return data;
}

export async function deleteRowLimitPolicy(
  datasourceId: string,
  policyId: string,
): Promise<void> {
  await apiClient.delete(`${base(datasourceId)}/${policyId}`);
}
