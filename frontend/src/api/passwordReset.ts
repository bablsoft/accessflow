import { apiClient } from './client';
import type { PasswordResetPreview, ResetPasswordInput } from '@/types/api';

export async function requestPasswordReset(email: string): Promise<void> {
  await apiClient.post('/api/v1/auth/password/forgot', { email });
}

export async function getPasswordResetPreview(token: string): Promise<PasswordResetPreview> {
  const { data } = await apiClient.get<PasswordResetPreview>(
    `/api/v1/auth/password/reset/${encodeURIComponent(token)}`,
  );
  return data;
}

export async function resetPassword(token: string, input: ResetPasswordInput): Promise<void> {
  await apiClient.post(
    `/api/v1/auth/password/reset/${encodeURIComponent(token)}`,
    input,
  );
}
