import { apiClient } from './client';
import type {
  ApiAiPreview,
  ApiDecisionResponse,
  ApiRequest,
  ApiRequestPage,
  GeneratedApiCall,
  PendingApiReviewPage,
  QueryStatus,
  SubmitApiRequestInput,
} from '@/types/api';

const BASE = '/api/v1/api-requests';
const REVIEW_BASE = '/api/v1/api-reviews';

export interface ApiRequestListFilters {
  page?: number;
  size?: number;
  status?: QueryStatus;
  connector_id?: string;
  verb?: string;
  submitted_by?: string;
  trace_id?: string;
  span_id?: string;
  from?: string;
  to?: string;
}

export interface ApiReviewListFilters {
  page?: number;
  size?: number;
  connector_id?: string;
  verb?: string;
}

export const apiRequestKeys = {
  all: ['api-requests'] as const,
  lists: () => ['api-requests', 'list'] as const,
  list: (filters: ApiRequestListFilters) => ['api-requests', 'list', filters] as const,
  details: () => ['api-requests', 'detail'] as const,
  detail: (id: string) => ['api-requests', 'detail', id] as const,
  reviewQueue: (filters: ApiReviewListFilters) => ['api-reviews', 'queue', filters] as const,
};

export async function listApiRequests(filters: ApiRequestListFilters = {}): Promise<ApiRequestPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.status) params.status = filters.status;
  if (filters.connector_id) params.connector_id = filters.connector_id;
  if (filters.verb) params.verb = filters.verb;
  if (filters.submitted_by) params.submitted_by = filters.submitted_by;
  if (filters.trace_id) params.trace_id = filters.trace_id;
  if (filters.span_id) params.span_id = filters.span_id;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  const { data } = await apiClient.get<ApiRequestPage>(BASE, { params });
  return data;
}

export interface ApiResponseDownload {
  blob: Blob;
  filename: string;
  contentType: string;
}

export async function downloadApiResponse(id: string): Promise<ApiResponseDownload> {
  const response = await apiClient.get(`${BASE}/${id}/response`, { responseType: 'blob' });
  const disposition = String(response.headers['content-disposition'] ?? '');
  const match = /filename="?([^"]+)"?/.exec(disposition);
  const filename = match?.[1] ?? `api-response-${id}`;
  const contentType = String(response.headers['content-type'] ?? 'application/octet-stream');
  return { blob: response.data as Blob, filename, contentType };
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
  filters: ApiReviewListFilters = {},
): Promise<PendingApiReviewPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.connector_id) params.connector_id = filters.connector_id;
  if (filters.verb) params.verb = filters.verb;
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
