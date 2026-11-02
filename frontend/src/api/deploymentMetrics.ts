import { apiClient } from './client';
import type { DeploymentEnvironmentMetricsBreakdown, DeploymentMetrics } from '@/types/api';

const PIPELINES = '/api/v1/deployment-pipelines';
const ORG_WIDE = '/api/v1/deployment-metrics';

export interface DeploymentMetricsFilters {
  /** ISO-8601 instant, inclusive. */
  from?: string;
  /** ISO-8601 instant, exclusive. */
  to?: string;
  /** Environment name, case-insensitive. */
  environment?: string;
}

export interface OrganizationDeploymentMetricsFilters extends DeploymentMetricsFilters {
  pipeline_id?: string;
}

/**
 * Its own factory, like `deploymentVersionKeys`: the org-wide read has no pipeline id, so hanging
 * it under the pipeline hierarchy would let a pipeline-CRUD `invalidateQueries` drop it.
 */
export const deploymentMetricKeys = {
  all: ['deployment-metrics'] as const,
  organization: (filters: OrganizationDeploymentMetricsFilters) =>
    ['deployment-metrics', 'organization', filters] as const,
  pipeline: (pipelineId: string, filters: DeploymentMetricsFilters) =>
    ['deployment-metrics', 'pipeline', pipelineId, filters] as const,
  breakdown: (filters: OrganizationDeploymentMetricsFilters) =>
    ['deployment-metrics', 'breakdown', filters] as const,
};

function toParams(filters: OrganizationDeploymentMetricsFilters): Record<string, string> {
  const params: Record<string, string> = {};
  if (filters.from) params.from = filters.from;
  if (filters.to) params.to = filters.to;
  if (filters.environment) params.environment = filters.environment;
  if (filters.pipeline_id) params.pipeline_id = filters.pipeline_id;
  return params;
}

export async function getOrganizationDeploymentMetrics(
  filters: OrganizationDeploymentMetricsFilters = {},
): Promise<DeploymentMetrics> {
  const { data } = await apiClient.get<DeploymentMetrics>(ORG_WIDE, {
    params: toParams(filters),
  });
  return data;
}

export async function getPipelineDeploymentMetrics(
  pipelineId: string,
  filters: DeploymentMetricsFilters = {},
): Promise<DeploymentMetrics> {
  const { data } = await apiClient.get<DeploymentMetrics>(`${PIPELINES}/${pipelineId}/metrics`, {
    params: toParams(filters),
  });
  return data;
}

/** Unpaginated by design — one entry per admin-curated environment. */
export async function getDeploymentEnvironmentMetrics(
  filters: OrganizationDeploymentMetricsFilters = {},
): Promise<DeploymentEnvironmentMetricsBreakdown> {
  const { data } = await apiClient.get<DeploymentEnvironmentMetricsBreakdown>(
    `${ORG_WIDE}/environments`,
    { params: toParams(filters) },
  );
  return data;
}
