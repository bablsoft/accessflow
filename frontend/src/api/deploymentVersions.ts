import { apiClient } from './client';
import type {
  DeploymentEnvironmentVersion,
  DeploymentEnvironmentVersionPage,
  DeploymentVersionHistoryPage,
  QueryStatus,
} from '@/types/api';

const PIPELINES = '/api/v1/deployment-pipelines';
const ORG_WIDE = '/api/v1/deployment-environment-versions';

export interface DeploymentEnvironmentVersionFilters {
  pipeline_id?: string;
  /** Exact match against any element of the environment's tags. */
  tag?: string;
  /** Environment name, case-insensitive exact match. */
  environment?: string;
  drifted?: boolean;
  page?: number;
  size?: number;
}

export interface DeploymentVersionHistoryFilters {
  status?: QueryStatus;
  page?: number;
  size?: number;
}

/**
 * Its own factory rather than a leaf on `deploymentPipelineKeys`: the org-wide matrix is a
 * top-level org resource with no pipeline id, so hanging it under the pipeline hierarchy would
 * let a pipeline-CRUD `invalidateQueries` drop an unrelated cache.
 */
export const deploymentVersionKeys = {
  all: ['deployment-versions'] as const,
  matrices: () => ['deployment-versions', 'matrix'] as const,
  matrix: (pipelineId: string) => ['deployment-versions', 'matrix', pipelineId] as const,
  lists: () => ['deployment-versions', 'list'] as const,
  list: (filters: DeploymentEnvironmentVersionFilters) =>
    ['deployment-versions', 'list', filters] as const,
  histories: () => ['deployment-versions', 'history'] as const,
  history: (
    pipelineId: string,
    environmentId: string,
    filters: DeploymentVersionHistoryFilters,
  ) => ['deployment-versions', 'history', pipelineId, environmentId, filters] as const,
};

/** Unpaginated by design — the environment list is admin-curated and small. */
export async function listPipelineEnvironmentVersions(
  pipelineId: string,
): Promise<DeploymentEnvironmentVersion[]> {
  const { data } = await apiClient.get<DeploymentEnvironmentVersion[]>(
    `${PIPELINES}/${pipelineId}/environment-versions`,
  );
  return data;
}

export async function listDeploymentEnvironmentVersions(
  filters: DeploymentEnvironmentVersionFilters = {},
): Promise<DeploymentEnvironmentVersionPage> {
  const params: Record<string, string | number | boolean> = {};
  if (filters.pipeline_id) params.pipeline_id = filters.pipeline_id;
  if (filters.tag) params.tag = filters.tag;
  if (filters.environment) params.environment = filters.environment;
  // Tri-state: `drifted=false` is the "up to date only" filter, so a truthiness guard here
  // would silently drop it.
  if (typeof filters.drifted === 'boolean') params.drifted = filters.drifted;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DeploymentEnvironmentVersionPage>(ORG_WIDE, { params });
  return data;
}

export async function listDeploymentEnvironmentHistory(
  pipelineId: string,
  environmentId: string,
  filters: DeploymentVersionHistoryFilters = {},
): Promise<DeploymentVersionHistoryPage> {
  const params: Record<string, string | number> = {};
  if (filters.status) params.status = filters.status;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<DeploymentVersionHistoryPage>(
    `${PIPELINES}/${pipelineId}/environments/${environmentId}/history`,
    { params },
  );
  return data;
}
