import { apiClient } from './client';

export interface StepUpCredential {
  password?: string;
  totpCode?: string;
}

export interface StepUpResult {
  step_up_token: string;
  expires_at: string;
}

/**
 * Re-verifies the caller's credential (password, or TOTP when 2FA is enrolled) and returns a
 * single-use step-up token (AF-444). The token authorises one push decision before it expires.
 */
export async function requestStepUp(credential: StepUpCredential): Promise<StepUpResult> {
  const body: { password?: string; totp_code?: string } = {};
  if (credential.password) body.password = credential.password;
  if (credential.totpCode) body.totp_code = credential.totpCode;
  const { data } = await apiClient.post<StepUpResult>('/api/v1/auth/step-up', body);
  return data;
}
