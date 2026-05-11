import { apiClient } from './client';
import type {
  ChangePasswordInput,
  ConfirmTotpInput,
  DisableTotpInput,
  MeProfile,
  TotpConfirmationResponse,
  TotpEnrollment,
  UpdateProfileInput,
} from '@/types/api';

export const meKeys = {
  current: ['me'] as const,
};

export async function getCurrentUser(): Promise<MeProfile> {
  const { data } = await apiClient.get<MeProfile>('/api/v1/me');
  return data;
}

export async function updateProfile(input: UpdateProfileInput): Promise<MeProfile> {
  const { data } = await apiClient.put<MeProfile>('/api/v1/me/profile', input);
  return data;
}

export async function changePassword(input: ChangePasswordInput): Promise<void> {
  await apiClient.post('/api/v1/me/password', input);
}

export async function enrollTotp(): Promise<TotpEnrollment> {
  const { data } = await apiClient.post<TotpEnrollment>('/api/v1/me/totp/enroll');
  return data;
}

export async function confirmTotp(input: ConfirmTotpInput): Promise<TotpConfirmationResponse> {
  const { data } = await apiClient.post<TotpConfirmationResponse>('/api/v1/me/totp/confirm', input);
  return data;
}

export async function disableTotp(input: DisableTotpInput): Promise<void> {
  await apiClient.post('/api/v1/me/totp/disable', input);
}
