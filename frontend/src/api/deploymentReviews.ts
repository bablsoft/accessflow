import { apiClient } from './client';
import type {
  DeploymentDecisionResult,
  DeploymentReviewPage,
  DeploymentRollbackReview,
  DeploymentRollbackReviewPage,
  DeploymentRollbackReviewStatus,
} from '@/types/api';

const BASE = '/api/v1/deployment-reviews';
const ROLLBACK_BASE = '/api/v1/deployment-rollback-reviews';

export interface DeploymentReviewListFilters {
  pipeline_id?: string;
  page?: number;
  size?: number;
}

export interface DeploymentRollbackReviewListFilters {
  status?: DeploymentRollbackReviewStatus;
  page?: number;
  size?: number;
}

export const deploymentReviewKeys = {
  all: ['deployment-reviews'] as const,
  lists: () => ['deployment-reviews', 'list'] as const,
  list: (filters: DeploymentReviewListFilters) => ['deployment-reviews', 'list', filters] as const,
};

export const deploymentRollbackReviewKeys = {
  all: ['deployment-rollback-reviews'] as const,
  lists: () => ['deployment-rollback-reviews', 'list'] as const,
  list: (filters: DeploymentRollbackReviewListFilters) =>
    ['deployment-rollback-reviews', 'list', filters] as const,
  detail: (id: string) => ['deployment-rollback-reviews', 'detail', id] as const,
};

export async function listDeploymentReviews(
  filters: DeploymentReviewListFilters = {},
): Promise<DeploymentReviewPage> {
  const params: Record<string, string | number> = {};
  if (filters.pipeline_id) params.pipeline_id = filters.pipeline_id;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DeploymentReviewPage>(BASE, { params });
  return data;
}

export async function approveDeployment(
  requestId: string,
  comment?: string,
): Promise<DeploymentDecisionResult> {
  const { data } = await apiClient.post<DeploymentDecisionResult>(`${BASE}/${requestId}/approve`, {
    comment: comment ?? null,
  });
  return data;
}

export async function rejectDeployment(
  requestId: string,
  comment?: string,
): Promise<DeploymentDecisionResult> {
  const { data } = await apiClient.post<DeploymentDecisionResult>(`${BASE}/${requestId}/reject`, {
    comment: comment ?? null,
  });
  return data;
}

export async function listDeploymentRollbackReviews(
  filters: DeploymentRollbackReviewListFilters = {},
): Promise<DeploymentRollbackReviewPage> {
  const params: Record<string, string | number> = {};
  if (filters.status) params.status = filters.status;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DeploymentRollbackReviewPage>(ROLLBACK_BASE, { params });
  return data;
}

export async function getDeploymentRollbackReview(id: string): Promise<DeploymentRollbackReview> {
  const { data } = await apiClient.get<DeploymentRollbackReview>(`${ROLLBACK_BASE}/${id}`);
  return data;
}

export async function acknowledgeDeploymentRollback(
  id: string,
  comment?: string,
): Promise<DeploymentRollbackReview> {
  const { data } = await apiClient.post<DeploymentRollbackReview>(
    `${ROLLBACK_BASE}/${id}/acknowledge`,
    { comment: comment ?? null },
  );
  return data;
}
