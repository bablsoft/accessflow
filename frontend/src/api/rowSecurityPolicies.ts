import { apiClient } from './client';
import type {
  CreateRowSecurityPolicyInput,
  RowSecurityPolicy,
  UpdateRowSecurityPolicyInput,
} from '@/types/api';

const base = (datasourceId: string) =>
  `/api/v1/datasources/${datasourceId}/row-security-policies`;

export const rowSecurityPolicyKeys = {
  all: ['row-security-policies'] as const,
  list: (datasourceId: string) => ['row-security-policies', 'list', datasourceId] as const,
};

export async function listRowSecurityPolicies(
  datasourceId: string,
): Promise<RowSecurityPolicy[]> {
  const { data } = await apiClient.get<{ content: RowSecurityPolicy[] }>(base(datasourceId));
  return data.content;
}

export async function createRowSecurityPolicy(
  datasourceId: string,
  input: CreateRowSecurityPolicyInput,
): Promise<RowSecurityPolicy> {
  const { data } = await apiClient.post<RowSecurityPolicy>(base(datasourceId), input);
  return data;
}

export async function updateRowSecurityPolicy(
  datasourceId: string,
  policyId: string,
  input: UpdateRowSecurityPolicyInput,
): Promise<RowSecurityPolicy> {
  const { data } = await apiClient.put<RowSecurityPolicy>(
    `${base(datasourceId)}/${policyId}`,
    input,
  );
  return data;
}

export async function deleteRowSecurityPolicy(
  datasourceId: string,
  policyId: string,
): Promise<void> {
  await apiClient.delete(`${base(datasourceId)}/${policyId}`);
}
