import { apiClient } from './client';
import type { ApiKey, CreateApiKeyInput, CreateApiKeyResponse } from '@/types/api';

export const apiKeysKeys = {
  list: ['api-keys'] as const,
};

export async function listApiKeys(): Promise<ApiKey[]> {
  const { data } = await apiClient.get<ApiKey[]>('/api/v1/me/api-keys');
  return data;
}

export async function createApiKey(input: CreateApiKeyInput): Promise<CreateApiKeyResponse> {
  const { data } = await apiClient.post<CreateApiKeyResponse>('/api/v1/me/api-keys', input);
  return data;
}

export async function revokeApiKey(id: string): Promise<void> {
  await apiClient.delete(`/api/v1/me/api-keys/${id}`);
}
