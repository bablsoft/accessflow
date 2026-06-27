import { apiClient } from './client';
import type {
  ApiAiPreview,
  ApiDecisionResponse,
  ApiRequest,
  ApiRequestPage,
  GeneratedApiCall,
  PendingApiReviewPage,
  SubmitApiRequestInput,
} from '@/types/api';

const BASE = '/api/v1/api-requests';
const REVIEW_BASE = '/api/v1/api-reviews';

export interface ApiRequestListFilters {
  page?: number;
  size?: number;
}

export const apiRequestKeys = {
  all: ['api-requests'] as const,
  lists: () => ['api-requests', 'list'] as const,
  list: (filters: ApiRequestListFilters) => ['api-requests', 'list', filters] as const,
  details: () => ['api-requests', 'detail'] as const,
  detail: (id: string) => ['api-requests', 'detail', id] as const,
  reviewQueue: (filters: ApiRequestListFilters) => ['api-reviews', 'queue', filters] as const,
};

export async function listApiRequests(filters: ApiRequestListFilters = {}): Promise<ApiRequestPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<ApiRequestPage>(BASE, { params });
  return data;
}

export async function getApiRequest(id: string): Promise<ApiRequest> {
  const { data } = await apiClient.get<ApiRequest>(`${BASE}/${id}`);
  return data;
}

export async function submitApiRequest(input: SubmitApiRequestInput): Promise<ApiRequest> {
  const { data } = await apiClient.post<ApiRequest>(BASE, input);
  return data;
}

export async function cancelApiRequest(id: string): Promise<void> {
  await apiClient.post(`${BASE}/${id}/cancel`);
}

export async function executeApiRequest(id: string): Promise<ApiRequest> {
  const { data } = await apiClient.post<ApiRequest>(`${BASE}/${id}/execute`);
  return data;
}

export async function analyzeApiCall(input: {
  connector_id: string;
  operation_id?: string | null;
  verb?: string;
  request_path?: string;
  request_body?: string | null;
  language?: string;
}): Promise<ApiAiPreview> {
  const { data } = await apiClient.post<ApiAiPreview>(`${BASE}/analyze`, input);
  return data;
}

export async function generateApiCall(input: {
  connector_id: string;
  prompt: string;
  language?: string;
}): Promise<GeneratedApiCall> {
  const { data } = await apiClient.post<GeneratedApiCall>(`${BASE}/generate`, input);
  return data;
}

export async function listPendingApiReviews(
  filters: ApiRequestListFilters = {},
): Promise<PendingApiReviewPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<PendingApiReviewPage>(REVIEW_BASE, { params });
  return data;
}

export async function approveApiReview(id: string, comment?: string): Promise<ApiDecisionResponse> {
  const { data } = await apiClient.post<ApiDecisionResponse>(`${REVIEW_BASE}/${id}/approve`, {
    comment,
  });
  return data;
}

export async function rejectApiReview(id: string, comment?: string): Promise<ApiDecisionResponse> {
  const { data } = await apiClient.post<ApiDecisionResponse>(`${REVIEW_BASE}/${id}/reject`, {
    comment,
  });
  return data;
}
