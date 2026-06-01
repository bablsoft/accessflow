import { apiClient } from './client';
import type {
  CreateMaskingPolicyInput,
  MaskingPolicy,
  UpdateMaskingPolicyInput,
} from '@/types/api';

const base = (datasourceId: string) =>
  `/api/v1/datasources/${datasourceId}/masking-policies`;

export const maskingPolicyKeys = {
  all: ['masking-policies'] as const,
  list: (datasourceId: string) => ['masking-policies', 'list', datasourceId] as const,
};

export async function listMaskingPolicies(datasourceId: string): Promise<MaskingPolicy[]> {
  const { data } = await apiClient.get<{ content: MaskingPolicy[] }>(base(datasourceId));
  return data.content;
}

export async function createMaskingPolicy(
  datasourceId: string,
  input: CreateMaskingPolicyInput,
): Promise<MaskingPolicy> {
  const { data } = await apiClient.post<MaskingPolicy>(base(datasourceId), input);
  return data;
}

export async function updateMaskingPolicy(
  datasourceId: string,
  policyId: string,
  input: UpdateMaskingPolicyInput,
): Promise<MaskingPolicy> {
  const { data } = await apiClient.put<MaskingPolicy>(
    `${base(datasourceId)}/${policyId}`,
    input,
  );
  return data;
}

export async function deleteMaskingPolicy(
  datasourceId: string,
  policyId: string,
): Promise<void> {
  await apiClient.delete(`${base(datasourceId)}/${policyId}`);
}
