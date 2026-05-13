import { apiClient } from './client';
import type { AuthUser, LoginPayload } from './auth';

export interface SetupStatus {
  setup_required: boolean;
}

export interface SetupRequest {
  organization_name: string;
  email: string;
  display_name?: string;
  password: string;
}

interface RawSetupResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  user: AuthUser;
}

export async function getSetupStatus(): Promise<SetupStatus> {
  const { data } = await apiClient.get<SetupStatus>('/api/v1/auth/setup-status');
  return data;
}

export async function submitSetup(req: SetupRequest): Promise<LoginPayload> {
  const { data } = await apiClient.post<RawSetupResponse>('/api/v1/auth/setup', req);
  return {
    access_token: data.access_token,
    expires_in: data.expires_in,
    user: data.user,
  };
}
