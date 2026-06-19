import { apiClient } from './client';
import type {
  ComplianceReport,
  ComplianceReportFormat,
  ComplianceReportType,
  ComplianceSigningCertificate,
} from '@/types/api';

const BASE = '/api/v1/admin/compliance';

export interface ComplianceReportParams {
  from: string;
  to: string;
  datasourceId?: string;
}

export interface ComplianceExportResult {
  blob: Blob;
  filename: string;
  signature: string | null;
  signatureAlgorithm: string | null;
  contentSha256: string | null;
  truncated: boolean;
}

export const complianceKeys = {
  all: ['compliance'] as const,
  report: (type: ComplianceReportType, params: ComplianceReportParams) =>
    ['compliance', 'report', type, params] as const,
  signingCertificate: () => ['compliance', 'signing-certificate'] as const,
};

function toQuery(params: ComplianceReportParams): Record<string, string> {
  const query: Record<string, string> = { from: params.from, to: params.to };
  if (params.datasourceId) query.datasourceId = params.datasourceId;
  return query;
}

export async function fetchClassifiedAccessReport(
  params: ComplianceReportParams,
): Promise<ComplianceReport> {
  const { data } = await apiClient.get<ComplianceReport>(`${BASE}/reports/classified-access`, {
    params: toQuery(params),
  });
  return data;
}

export async function fetchRegulatoryAuditTrail(
  params: ComplianceReportParams,
): Promise<ComplianceReport> {
  const { data } = await apiClient.get<ComplianceReport>(`${BASE}/reports/regulatory-audit-trail`, {
    params: toQuery(params),
  });
  return data;
}

export function fetchComplianceReport(
  type: ComplianceReportType,
  params: ComplianceReportParams,
): Promise<ComplianceReport> {
  return type === 'CLASSIFIED_ACCESS'
    ? fetchClassifiedAccessReport(params)
    : fetchRegulatoryAuditTrail(params);
}

export async function fetchSigningCertificate(): Promise<ComplianceSigningCertificate> {
  const { data } = await apiClient.get<ComplianceSigningCertificate>(`${BASE}/signing-certificate`);
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

export async function exportComplianceReport(
  type: ComplianceReportType,
  format: ComplianceReportFormat,
  params: ComplianceReportParams,
): Promise<ComplianceExportResult> {
  const response = await apiClient.get<Blob>(`${BASE}/reports/export`, {
    params: { type, format, ...toQuery(params) },
    responseType: 'blob',
  });
  const fallback = `compliance-${type.toLowerCase().replace(/_/g, '-')}.${format.toLowerCase()}`;
  const truncated = header(response.headers['x-accessflow-export-truncated']);
  return {
    blob: response.data,
    filename: parseFilename(response.headers['content-disposition'] as string | undefined) ?? fallback,
    signature: header(response.headers['x-accessflow-signature']),
    signatureAlgorithm: header(response.headers['x-accessflow-signature-algorithm']),
    contentSha256: header(response.headers['x-accessflow-content-sha256']),
    truncated: truncated?.toLowerCase() === 'true',
  };
}
