import { apiClient } from './client';
import type {
  CreateDataClassificationTagInput,
  DataClassificationDerivation,
  DataClassificationTag,
  OrganizationDataClassification,
} from '@/types/api';

const base = (datasourceId: string) =>
  `/api/v1/datasources/${datasourceId}/classification-tags`;

export const dataClassificationKeys = {
  all: ['data-classifications'] as const,
  list: (datasourceId: string) => ['data-classifications', 'list', datasourceId] as const,
  derivation: (datasourceId: string) =>
    ['data-classifications', 'derivation', datasourceId] as const,
  orgAll: ['data-classifications', 'org'] as const,
};

export async function listClassificationTags(
  datasourceId: string,
): Promise<DataClassificationTag[]> {
  const { data } = await apiClient.get<{ content: DataClassificationTag[] }>(base(datasourceId));
  return data.content;
}

export async function createClassificationTags(
  datasourceId: string,
  input: CreateDataClassificationTagInput,
): Promise<DataClassificationTag[]> {
  const { data } = await apiClient.post<{ content: DataClassificationTag[] }>(
    base(datasourceId),
    input,
  );
  return data.content;
}

export async function deleteClassificationTag(
  datasourceId: string,
  tagId: string,
): Promise<void> {
  await apiClient.delete(`${base(datasourceId)}/${tagId}`);
}

export async function getDerivationPreview(
  datasourceId: string,
): Promise<DataClassificationDerivation> {
  const { data } = await apiClient.get<DataClassificationDerivation>(
    `${base(datasourceId)}/derivation-preview`,
  );
  return data;
}

export async function listOrganizationClassifications(): Promise<OrganizationDataClassification[]> {
  const { data } = await apiClient.get<{ content: OrganizationDataClassification[] }>(
    '/api/v1/admin/data-classifications',
  );
  return data.content;
}
