import { apiClient } from './client';
import type {
  ApiConnectorClassificationDerivation,
  ApiConnectorClassificationTag,
  CreateApiConnectorClassificationTagInput,
} from '@/types/api';

const base = (connectorId: string) => `/api/v1/api-connectors/${connectorId}/classification-tags`;

export const apiConnectorClassificationKeys = {
  all: ['api-connector-classifications'] as const,
  list: (connectorId: string) =>
    ['api-connector-classifications', 'list', connectorId] as const,
  derivation: (connectorId: string) =>
    ['api-connector-classifications', 'derivation', connectorId] as const,
};

export async function listApiConnectorClassificationTags(
  connectorId: string,
): Promise<ApiConnectorClassificationTag[]> {
  const { data } = await apiClient.get<{ content: ApiConnectorClassificationTag[] }>(
    base(connectorId),
  );
  return data.content;
}

export async function createApiConnectorClassificationTags(
  connectorId: string,
  input: CreateApiConnectorClassificationTagInput,
): Promise<ApiConnectorClassificationTag[]> {
  const { data } = await apiClient.post<{ content: ApiConnectorClassificationTag[] }>(
    base(connectorId),
    input,
  );
  return data.content;
}

export async function deleteApiConnectorClassificationTag(
  connectorId: string,
  tagId: string,
): Promise<void> {
  await apiClient.delete(`${base(connectorId)}/${tagId}`);
}

export async function getApiConnectorDerivationPreview(
  connectorId: string,
): Promise<ApiConnectorClassificationDerivation> {
  const { data } = await apiClient.get<ApiConnectorClassificationDerivation>(
    `${base(connectorId)}/derivation-preview`,
  );
  return data;
}
