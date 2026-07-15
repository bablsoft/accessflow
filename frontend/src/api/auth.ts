import { apiClient } from './client';
import type { AuthProvider, OAuth2ProviderSummary } from '@/types/api';

export interface AuthUser {
  id: string;
  email: string;
  display_name: string;
  /** Effective role NAME — a system role ("ADMIN", …) or a custom role's name (AF-522). */
  role: string;
  role_id: string | null;
  /** Resolved functional permissions of the user's role (AF-522) — drives all gating. */
  permissions: string[];
  auth_provider: AuthProvider;
  totp_enabled: boolean;
  platform_admin: boolean;
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

export const oauth2ProvidersKeys = {
  all: ['oauth2Providers'] as const,
};

export const samlEnabledKeys = {
  all: ['samlEnabled'] as const,
};

export async function listOAuth2Providers(): Promise<OAuth2ProviderSummary[]> {
  const { data } = await apiClient.get<OAuth2ProviderSummary[]>(
    '/api/v1/auth/oauth2/providers',
  );
  return data;
}

export async function getSamlEnabled(): Promise<boolean> {
  const { data } = await apiClient.get<{ enabled: boolean }>('/api/v1/auth/saml/enabled');
  return data.enabled;
}

export async function exchangeOAuth2Code(code: string): Promise<LoginPayload> {
  const { data } = await apiClient.post<RawLoginResponse>(
    '/api/v1/auth/oauth2/exchange',
    { code },
  );
  return { access_token: data.access_token, expires_in: data.expires_in, user: data.user };
}

export async function exchangeSamlCode(code: string): Promise<LoginPayload> {
  const { data } = await apiClient.post<RawLoginResponse>(
    '/api/v1/auth/saml/exchange',
    { code },
  );
  return { access_token: data.access_token, expires_in: data.expires_in, user: data.user };
}
