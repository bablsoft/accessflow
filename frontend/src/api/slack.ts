import { isAxiosError } from 'axios';
import { apiClient } from './client';
import type {
  SlackAppConfig,
  SlackLinkCode,
  SlackLinkStatus,
  TestSlackResult,
  UpsertSlackAppConfigInput,
} from '@/types/api';

const ADMIN_BASE = '/api/v1/admin/slack-app-config';
const INTEGRATION_BASE = '/api/v1/integrations/slack';

export const slackAppConfigKeys = {
  all: ['slackAppConfig'] as const,
  current: () => ['slackAppConfig', 'current'] as const,
};

export const slackLinkKeys = {
  all: ['slackLink'] as const,
  status: () => ['slackLink', 'status'] as const,
};

/** Returns the Slack app config, or null when none is configured (404). */
export async function getSlackAppConfig(): Promise<SlackAppConfig | null> {
  try {
    const { data } = await apiClient.get<SlackAppConfig>(ADMIN_BASE);
    return data;
  } catch (err) {
    if (isAxiosError(err) && err.response?.status === 404) {
      return null;
    }
    throw err;
  }
}

export async function upsertSlackAppConfig(
  input: UpsertSlackAppConfigInput,
): Promise<SlackAppConfig> {
  const { data } = await apiClient.put<SlackAppConfig>(ADMIN_BASE, input);
  return data;
}

export async function deleteSlackAppConfig(): Promise<void> {
  await apiClient.delete(ADMIN_BASE);
}

export async function testSlackAppConfig(): Promise<TestSlackResult> {
  const { data } = await apiClient.post<TestSlackResult>(`${ADMIN_BASE}/test`);
  return data;
}

export async function getSlackLinkStatus(): Promise<SlackLinkStatus> {
  const { data } = await apiClient.get<SlackLinkStatus>(`${INTEGRATION_BASE}/link`);
  return data;
}

export async function createSlackLinkCode(): Promise<SlackLinkCode> {
  const { data } = await apiClient.post<SlackLinkCode>(`${INTEGRATION_BASE}/link-codes`);
  return data;
}

export async function unlinkSlack(): Promise<void> {
  await apiClient.delete(`${INTEGRATION_BASE}/link`);
}
