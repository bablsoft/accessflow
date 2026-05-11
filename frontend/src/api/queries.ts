import { apiClient } from './client';
import type {
  AiAnalysis,
  ExecuteQueryResponse,
  PaginatedResponse,
  QueryDetail,
  QueryListItem,
  QueryResultsPage,
  QueryStatus,
  QueryType,
  SubmitQueryResponse,
} from '@/types/api';

const BASE = '/api/v1/queries';

export interface SubmitQueryInput {
  datasource_id: string;
  sql: string;
  justification: string;
}

export interface AnalyzeQueryInput {
  datasource_id: string;
  sql: string;
}

export interface QueryListFilters {
  status?: QueryStatus;
  datasource_id?: string;
  submitted_by?: string;
  from?: string;
  to?: string;
  query_type?: QueryType;
  page?: number;
  size?: number;
}

export const queryKeys = {
  all: ['queries'] as const,
  lists: () => ['queries', 'list'] as const,
  list: (filters: QueryListFilters) => ['queries', 'list', filters] as const,
  details: () => ['queries', 'detail'] as const,
  detail: (id: string) => ['queries', 'detail', id] as const,
  results: (id: string, page: number, size: number) =>
    ['queries', 'detail', id, 'results', page, size] as const,
};

export async function submitQuery(input: SubmitQueryInput): Promise<SubmitQueryResponse> {
  const { data } = await apiClient.post<SubmitQueryResponse>(BASE, input);
  return data;
}

export async function listQueries(
  filters: QueryListFilters = {},
): Promise<PaginatedResponse<QueryListItem>> {
  const { data } = await apiClient.get<PaginatedResponse<QueryListItem>>(BASE, {
    params: toQueryParams(filters),
  });
  return data;
}

export async function getQuery(id: string): Promise<QueryDetail> {
  const { data } = await apiClient.get<QueryDetail>(`${BASE}/${id}`);
  return data;
}

export async function cancelQuery(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function executeQuery(id: string): Promise<ExecuteQueryResponse> {
  const { data } = await apiClient.post<ExecuteQueryResponse>(`${BASE}/${id}/execute`);
  return data;
}

export async function getQueryResults(
  id: string,
  page = 0,
  size = 100,
): Promise<QueryResultsPage> {
  const { data } = await apiClient.get<QueryResultsPage>(`${BASE}/${id}/results`, {
    params: { page, size },
  });
  return data;
}

export async function analyzeOnly(input: AnalyzeQueryInput): Promise<AiAnalysis> {
  const { data } = await apiClient.post<AiAnalysis>(`${BASE}/analyze`, input);
  return data;
}

export interface QueryExportResult {
  blob: Blob;
  filename: string;
  truncated: boolean;
}

export async function exportQueriesCsv(
  filters: QueryListFilters = {},
): Promise<QueryExportResult> {
  const response = await apiClient.get<Blob>(`${BASE}/export.csv`, {
    params: toQueryParams(stripPagination(filters)),
    responseType: 'blob',
  });
  const disposition = response.headers['content-disposition'];
  const filename =
    parseContentDispositionFilename(typeof disposition === 'string' ? disposition : undefined) ??
    defaultExportFilename();
  const truncatedHeader = response.headers['x-accessflow-export-truncated'];
  return {
    blob: response.data,
    filename,
    truncated: typeof truncatedHeader === 'string' && truncatedHeader.toLowerCase() === 'true',
  };
}

export const isPending = (s: QueryStatus): boolean =>
  s === 'PENDING_AI' || s === 'PENDING_REVIEW';

function toQueryParams(filters: QueryListFilters): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  if (filters.status) params.status = filters.status;
  if (filters.datasource_id) params.datasourceId = filters.datasource_id;
  if (filters.submitted_by) params.submittedBy = filters.submitted_by;
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  if (filters.query_type) params.queryType = filters.query_type;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  return params;
}

function stripPagination(filters: QueryListFilters): QueryListFilters {
  const next: QueryListFilters = { ...filters };
  delete next.page;
  delete next.size;
  return next;
}

function parseContentDispositionFilename(header: string | undefined): string | null {
  if (!header) return null;
  const match = /filename="([^"]+)"/i.exec(header);
  return match?.[1] ?? null;
}

function defaultExportFilename(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp =
    `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
    `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}${pad(now.getUTCSeconds())}`;
  return `queries-${stamp}.csv`;
}
