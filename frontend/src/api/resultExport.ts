import { apiClient } from './client';
import type { ExportDecision, ResultExportFormat } from '@/types/api';

const base = (queryId: string) => `/api/v1/queries/${queryId}/results`;

export const resultExportKeys = {
  all: ['result-export'] as const,
  decision: (queryId: string) => ['result-export', 'decision', queryId] as const,
};

export interface ResultExportDownload {
  blob: Blob;
  filename: string;
  signature: string | null;
  signatureAlgorithm: string | null;
  contentSha256: string | null;
  truncated: boolean;
}

export async function fetchExportDecision(queryId: string): Promise<ExportDecision> {
  const { data } = await apiClient.get<ExportDecision>(`${base(queryId)}/export-decision`);
  return data;
}

const FILENAME_RE = /filename="?([^"]+)"?/i;

function parseFilename(disposition: string | null | undefined): string | null {
  if (!disposition) return null;
  const match = FILENAME_RE.exec(disposition);
  return match?.[1] ?? null;
}

function header(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

export async function downloadResultExport(
  queryId: string,
  format: ResultExportFormat,
): Promise<ResultExportDownload> {
  const response = await apiClient.get<Blob>(`${base(queryId)}/export`, {
    params: { format },
    responseType: 'blob',
  });
  const fallback = `query-results-${queryId.slice(0, 8)}.${format.toLowerCase()}`;
  const truncated = header(response.headers['x-accessflow-export-truncated']);
  return {
    blob: response.data,
    filename:
      parseFilename(response.headers['content-disposition'] as string | undefined) ?? fallback,
    signature: header(response.headers['x-accessflow-signature']),
    signatureAlgorithm: header(response.headers['x-accessflow-signature-algorithm']),
    contentSha256: header(response.headers['x-accessflow-content-sha256']),
    truncated: truncated?.toLowerCase() === 'true',
  };
}
