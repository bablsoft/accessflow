import { apiClient } from './client';
import type { PrivilegedAccessPage, StandingBypassKind } from '@/types/api';

const BASE = '/api/v1/admin/privileged-access';

export interface PrivilegedAccessFilters {
  page?: number;
  size?: number;
  /** Row filter — a kept row still lists every kind that applies to it. */
  kind?: StandingBypassKind;
  user_id?: string;
}

export const privilegedAccessKeys = {
  all: ['privileged-access'] as const,
  reports: () => ['privileged-access', 'report'] as const,
  report: (filters: PrivilegedAccessFilters) => ['privileged-access', 'report', filters] as const,
};

/**
 * The privileged-access report (#968): every identity that can reach data without a permission
 * row — QUERY_ADMIN holders and break-glass grantees — one row each, with query evidence.
 */
export async function listPrivilegedAccess(
  filters: PrivilegedAccessFilters,
): Promise<PrivilegedAccessPage> {
  const response = await apiClient.get<PrivilegedAccessPage>(BASE, { params: filters });
  return response.data;
}
