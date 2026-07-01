import { apiClient } from './client';
import type {
  ApiConnectorMaskingPolicy,
  CreateApiConnectorMaskingPolicyInput,
  UpdateApiConnectorMaskingPolicyInput,
} from '@/types/api';

const base = (connectorId: string) => `/api/v1/api-connectors/${connectorId}/masking-policies`;

export const apiConnectorMaskingPolicyKeys = {
  all: ['api-connector-masking-policies'] as const,
  list: (connectorId: string) =>
    ['api-connector-masking-policies', 'list', connectorId] as const,
};

export async function listApiConnectorMaskingPolicies(
  connectorId: string,
): Promise<ApiConnectorMaskingPolicy[]> {
  const { data } = await apiClient.get<{ content: ApiConnectorMaskingPolicy[] }>(base(connectorId));
  return data.content;
}

export async function createApiConnectorMaskingPolicy(
  connectorId: string,
  input: CreateApiConnectorMaskingPolicyInput,
): Promise<ApiConnectorMaskingPolicy> {
  const { data } = await apiClient.post<ApiConnectorMaskingPolicy>(base(connectorId), input);
  return data;
}

export async function updateApiConnectorMaskingPolicy(
  connectorId: string,
  policyId: string,
  input: UpdateApiConnectorMaskingPolicyInput,
): Promise<ApiConnectorMaskingPolicy> {
  const { data } = await apiClient.put<ApiConnectorMaskingPolicy>(
    `${base(connectorId)}/${policyId}`,
    input,
  );
  return data;
}

export async function deleteApiConnectorMaskingPolicy(
  connectorId: string,
  policyId: string,
): Promise<void> {
  await apiClient.delete(`${base(connectorId)}/${policyId}`);
}
