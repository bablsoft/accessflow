import { apiClient } from './client';
import type {
  HelpAgentConfig,
  HelpAgentTestResult,
  UpdateHelpAgentConfigInput,
} from '@/types/api';

const BASE = '/api/v1/admin/help-agent';

export const helpAgentKeys = {
  all: ['helpAgent'] as const,
  config: () => ['helpAgent', 'config'] as const,
};

/** Never 404s: an organization that has never saved a row is served the defaults. */
export async function getHelpAgentConfig(): Promise<HelpAgentConfig> {
  const { data } = await apiClient.get<HelpAgentConfig>(BASE);
  return data;
}

export async function updateHelpAgentConfig(
  input: UpdateHelpAgentConfigInput,
): Promise<HelpAgentConfig> {
  const { data } = await apiClient.put<HelpAgentConfig>(BASE, input);
  return data;
}

/** Always 200; the outcome is in the body. */
export async function testHelpAgentConfig(): Promise<HelpAgentTestResult> {
  const { data } = await apiClient.post<HelpAgentTestResult>(`${BASE}/test`);
  return data;
}

/** Accepted asynchronously — the outcome lands on the config row, there is nothing to poll. */
export async function reindexHelpCorpus(): Promise<void> {
  await apiClient.post(`${BASE}/reindex`);
}
