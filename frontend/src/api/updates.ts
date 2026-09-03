import { apiClient } from './client';
import type { UpdateStatus } from '@/types/api';

const BASE = '/api/v1/system/update-status';

export const updateKeys = {
  all: ['updates'] as const,
  status: () => ['updates', 'status'] as const,
};

/** Install-level "is a newer stable release out?" snapshot; readable by every signed-in user. */
export async function fetchUpdateStatus(): Promise<UpdateStatus> {
  const { data } = await apiClient.get<UpdateStatus>(BASE);
  return data;
}
