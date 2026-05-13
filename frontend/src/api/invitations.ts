import { apiClient } from './client';
import type { AcceptInvitationInput, InvitationPreview } from '@/types/api';

export async function getInvitationPreview(token: string): Promise<InvitationPreview> {
  const { data } = await apiClient.get<InvitationPreview>(
    `/api/v1/auth/invitations/${encodeURIComponent(token)}`,
  );
  return data;
}

export async function acceptInvitation(
  token: string,
  input: AcceptInvitationInput,
): Promise<void> {
  await apiClient.post(
    `/api/v1/auth/invitations/${encodeURIComponent(token)}/accept`,
    input,
  );
}
