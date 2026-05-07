import { apiClient } from './client';

export interface SetupStatus {
  setup_required: boolean;
}

export interface SetupRequest {
  organization_name: string;
  email: string;
  display_name?: string;
  password: string;
}

export async function getSetupStatus(): Promise<SetupStatus> {
  const { data } = await apiClient.get<SetupStatus>('/api/v1/auth/setup-status');
  return data;
}

export async function submitSetup(req: SetupRequest): Promise<void> {
  await apiClient.post('/api/v1/auth/setup', req);
}
