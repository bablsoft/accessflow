import { apiClient } from './client';
import type { CreateDatasourceReviewerInput, DatasourceReviewer } from '@/types/api';

const base = (datasourceId: string) => `/api/v1/datasources/${datasourceId}/reviewers`;

export const datasourceReviewerKeys = {
  all: ['datasourceReviewers'] as const,
  list: (datasourceId: string) => ['datasourceReviewers', 'list', datasourceId] as const,
};

export async function listReviewers(datasourceId: string): Promise<DatasourceReviewer[]> {
  const { data } = await apiClient.get<{ reviewers: DatasourceReviewer[] }>(base(datasourceId));
  return data.reviewers;
}

export async function addReviewer(
  datasourceId: string,
  input: CreateDatasourceReviewerInput,
): Promise<DatasourceReviewer> {
  const body: Record<string, string> = {};
  if (input.userId) body.user_id = input.userId;
  if (input.groupId) body.group_id = input.groupId;
  const { data } = await apiClient.post<DatasourceReviewer>(base(datasourceId), body);
  return data;
}

export async function removeReviewer(
  datasourceId: string,
  reviewerId: string,
): Promise<void> {
  await apiClient.delete(`${base(datasourceId)}/${reviewerId}`);
}
