import { apiClient } from './client';
import type { ApiConnectorUsage } from '@/types/api';

/** A UTC date window (YYYY-MM-DD, inclusive); both absent = the backend's current month. */
export interface ApiConnectorUsageRange {
  from?: string;
  to?: string;
}

export const apiConnectorUsageKeys = {
  all: ['api-connector-usage'] as const,
  connector: (connectorId: string) => ['api-connector-usage', connectorId] as const,
  usage: (connectorId: string, from?: string, to?: string) =>
    ['api-connector-usage', connectorId, from ?? null, to ?? null] as const,
};

export async function getApiConnectorUsage(
  connectorId: string,
  range: ApiConnectorUsageRange = {},
): Promise<ApiConnectorUsage> {
  const params: Record<string, string> = {};
  if (range.from) params.from = range.from;
  if (range.to) params.to = range.to;
  const { data } = await apiClient.get<ApiConnectorUsage>(
    `/api/v1/api-connectors/${connectorId}/usage`,
    { params },
  );
  return data;
}
