import { apiClient } from './client';
import type {
  GroupDecision,
  PendingGroupReviewPage,
  RequestGroup,
  RequestGroupPage,
  RequestGroupStatus,
  SaveRequestGroupInput,
  SubmitRequestGroupInput,
} from '@/types/api';

const BASE = '/api/v1/request-groups';

export interface RequestGroupListFilters {
  page?: number;
  size?: number;
  status?: RequestGroupStatus;
  submitted_by?: string;
}

export interface RequestGroupReviewFilters {
  page?: number;
  size?: number;
}

export const requestGroupKeys = {
  all: ['request-groups'] as const,
  lists: () => ['request-groups', 'list'] as const,
  list: (filters: RequestGroupListFilters) => ['request-groups', 'list', filters] as const,
  details: () => ['request-groups', 'detail'] as const,
  detail: (id: string) => ['request-groups', 'detail', id] as const,
  reviews: (filters: RequestGroupReviewFilters) => ['request-groups', 'reviews', filters] as const,
};

export async function listRequestGroups(
  filters: RequestGroupListFilters = {},
): Promise<RequestGroupPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.status) params.status = filters.status;
  if (filters.submitted_by) params.submitted_by = filters.submitted_by;
  const { data } = await apiClient.get<RequestGroupPage>(BASE, { params });
  return data;
}

export async function getRequestGroup(id: string): Promise<RequestGroup> {
  const { data } = await apiClient.get<RequestGroup>(`${BASE}/${id}`);
  return data;
}

export async function createRequestGroup(input: SaveRequestGroupInput): Promise<RequestGroup> {
  const { data } = await apiClient.post<RequestGroup>(BASE, input);
  return data;
}

export async function updateRequestGroup(
  id: string,
  input: SaveRequestGroupInput,
): Promise<RequestGroup> {
  const { data } = await apiClient.put<RequestGroup>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteRequestGroup(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function submitRequestGroup(
  id: string,
  input: SubmitRequestGroupInput,
): Promise<void> {
  await apiClient.post(`${BASE}/${id}/submit`, input);
}

export async function executeRequestGroup(id: string): Promise<RequestGroup> {
  const { data } = await apiClient.post<RequestGroup>(`${BASE}/${id}/execute`);
  return data;
}

export async function cancelRequestGroup(id: string): Promise<RequestGroup> {
  const { data } = await apiClient.post<RequestGroup>(`${BASE}/${id}/cancel`);
  return data;
}

export async function listPendingGroupReviews(
  filters: RequestGroupReviewFilters = {},
): Promise<PendingGroupReviewPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<PendingGroupReviewPage>(`${BASE}/reviews`, { params });
  return data;
}

export async function approveRequestGroup(id: string, comment?: string): Promise<GroupDecision> {
  const { data } = await apiClient.post<GroupDecision>(`${BASE}/${id}/approve`, { comment });
  return data;
}

export async function rejectRequestGroup(id: string, comment?: string): Promise<GroupDecision> {
  const { data } = await apiClient.post<GroupDecision>(`${BASE}/${id}/reject`, { comment });
  return data;
}
