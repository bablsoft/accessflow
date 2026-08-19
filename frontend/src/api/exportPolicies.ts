import { apiClient } from './client';
import type {
  CreateExportPolicyInput,
  ExportPolicy,
  UpdateExportPolicyInput,
} from '@/types/api';

const base = (datasourceId: string) =>
  `/api/v1/datasources/${datasourceId}/export-policies`;

export const exportPolicyKeys = {
  all: ['export-policies'] as const,
  list: (datasourceId: string) => ['export-policies', 'list', datasourceId] as const,
};

export async function listExportPolicies(datasourceId: string): Promise<ExportPolicy[]> {
  const { data } = await apiClient.get<{ content: ExportPolicy[] }>(base(datasourceId));
  return data.content;
}

export async function createExportPolicy(
  datasourceId: string,
  input: CreateExportPolicyInput,
): Promise<ExportPolicy> {
  const { data } = await apiClient.post<ExportPolicy>(base(datasourceId), input);
  return data;
}

export async function updateExportPolicy(
  datasourceId: string,
  policyId: string,
  input: UpdateExportPolicyInput,
): Promise<ExportPolicy> {
  const { data } = await apiClient.put<ExportPolicy>(
    `${base(datasourceId)}/${policyId}`,
    input,
  );
  return data;
}

export async function deleteExportPolicy(
  datasourceId: string,
  policyId: string,
): Promise<void> {
  await apiClient.delete(`${base(datasourceId)}/${policyId}`);
}
