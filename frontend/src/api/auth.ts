import { apiClient } from './client';
import type { AuthProvider, Role } from '@/types/api';

export interface AuthUser {
  id: string;
  email: string;
  display_name: string;
  role: Role;
  auth_provider: AuthProvider;
  totp_enabled: boolean;
  preferred_language: string | null;
}

export interface LoginPayload {
  access_token: string;
  expires_in: number;
  user: AuthUser;
}

interface RawLoginResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  user: AuthUser;
}

export async function login(
  email: string,
  password: string,
  totpCode?: string,
): Promise<LoginPayload> {
  const body: { email: string; password: string; totp_code?: string } = { email, password };
  if (totpCode) body.totp_code = totpCode;
  const { data } = await apiClient.post<RawLoginResponse>('/api/v1/auth/login', body);
  return { access_token: data.access_token, expires_in: data.expires_in, user: data.user };
}

export async function refresh(): Promise<LoginPayload> {
  const { data } = await apiClient.post<RawLoginResponse>('/api/v1/auth/refresh');
  return { access_token: data.access_token, expires_in: data.expires_in, user: data.user };
}

export async function logout(): Promise<void> {
  await apiClient.post('/api/v1/auth/logout');
}
