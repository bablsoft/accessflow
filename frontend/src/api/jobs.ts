import { apiClient } from './client';
import type { JobExecutionPage, JobExecutionStatus, JobRegistry } from '@/types/api';

const BASE = '/api/v1/platform/jobs';

export interface JobExecutionListParams {
  status?: JobExecutionStatus;
  page?: number;
  size?: number;
}

export const jobKeys = {
  all: ['platform-jobs'] as const,
  registry: () => ['platform-jobs', 'registry'] as const,
  executions: (jobName: string, params: JobExecutionListParams) =>
    ['platform-jobs', 'executions', jobName, params] as const,
};

export async function getJobRegistry(): Promise<JobRegistry> {
  const { data } = await apiClient.get<JobRegistry>(BASE);
  return data;
}

export async function listJobExecutions(
  jobName: string,
  params: JobExecutionListParams = {},
): Promise<JobExecutionPage> {
  const query: Record<string, string | number> = {};
  if (params.status) query.status = params.status;
  if (typeof params.page === 'number') query.page = params.page;
  if (typeof params.size === 'number') query.size = params.size;
  const { data } = await apiClient.get<JobExecutionPage>(
    `${BASE}/${encodeURIComponent(jobName)}/executions`,
    { params: query },
  );
  return data;
}
