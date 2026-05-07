import { apiClient } from './client';
import type { PendingReviewsPage, ReviewDecisionResult } from '@/types/api';

const BASE = '/api/v1/reviews';

export interface PendingReviewsFilters {
  page?: number;
  size?: number;
}

export const reviewKeys = {
  all: ['reviews'] as const,
  pending: () => ['reviews', 'pending'] as const,
  pendingFor: (filters: PendingReviewsFilters) =>
    ['reviews', 'pending', filters] as const,
};

export async function listPendingReviews(
  filters: PendingReviewsFilters = {},
): Promise<PendingReviewsPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<PendingReviewsPage>(`${BASE}/pending`, { params });
  return data;
}

export async function approveQuery(
  queryId: string,
  comment?: string,
): Promise<ReviewDecisionResult> {
  const { data } = await apiClient.post<ReviewDecisionResult>(
    `${BASE}/${queryId}/approve`,
    { comment: comment ?? null },
  );
  return data;
}

export async function rejectQuery(
  queryId: string,
  comment?: string,
): Promise<ReviewDecisionResult> {
  const { data } = await apiClient.post<ReviewDecisionResult>(
    `${BASE}/${queryId}/reject`,
    { comment: comment ?? null },
  );
  return data;
}

export async function requestChanges(
  queryId: string,
  comment: string,
): Promise<ReviewDecisionResult> {
  const { data } = await apiClient.post<ReviewDecisionResult>(
    `${BASE}/${queryId}/request-changes`,
    { comment },
  );
  return data;
}
