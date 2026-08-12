import { apiClient } from './client';
import type {
  GrantResourceKind,
  GrantUsageRecommendation,
  OverProvisionedGrantPage,
} from '@/types/api';

const BASE = '/api/v1/admin/over-provisioned-access';

export interface OverProvisionedFilters {
  page?: number;
  size?: number;
  resource_kind?: GrantResourceKind;
  /** Repeatable — omit to include every recommendation. */
  recommendation?: GrantUsageRecommendation[];
  resource_id?: string;
  user_id?: string;
}

export const grantUsageKeys = {
  all: ['grant-usage'] as const,
  reports: () => ['grant-usage', 'report'] as const,
  report: (filters: OverProvisionedFilters) => ['grant-usage', 'report', filters] as const,
};

export interface OverProvisionedExportResult {
  blob: Blob;
  filename: string;
  truncated: boolean;
}

export async function listOverProvisionedGrants(
  filters: OverProvisionedFilters,
): Promise<OverProvisionedGrantPage> {
  const response = await apiClient.get<OverProvisionedGrantPage>(BASE, { params: filters });
  return response.data;
}

export async function exportOverProvisionedCsv(
  filters: OverProvisionedFilters,
): Promise<OverProvisionedExportResult> {
  // Page/size are meaningless for the export — the server applies its own row cap and reports
  // truncation via the header, so sending the current page would silently shrink the file.
  const exportFilters: Omit<OverProvisionedFilters, 'page' | 'size'> = {
    resource_kind: filters.resource_kind,
    recommendation: filters.recommendation,
    resource_id: filters.resource_id,
    user_id: filters.user_id,
  };
  const response = await apiClient.get<Blob>(`${BASE}/export.csv`, {
    params: exportFilters,
    responseType: 'blob',
  });
  const disposition = response.headers['content-disposition'];
  const truncatedHeader = response.headers['x-accessflow-export-truncated'];
  return {
    blob: response.data,
    filename:
      parseFilename(typeof disposition === 'string' ? disposition : undefined) ??
      'over-provisioned-access.csv',
    truncated: typeof truncatedHeader === 'string' && truncatedHeader.toLowerCase() === 'true',
  };
}

function parseFilename(header: string | undefined): string | null {
  if (!header) return null;
  const match = /filename="([^"]+)"/i.exec(header);
  return match?.[1] ?? null;
}
