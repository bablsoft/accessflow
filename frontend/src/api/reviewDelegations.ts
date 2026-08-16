import { apiClient } from './client';
import type {
  CreateReviewDelegationInput,
  DelegateCandidate,
  MyReviewDelegations,
  ReviewDelegation,
} from '@/types/api';

const BASE = '/api/v1/me/review-delegations';

export const reviewDelegationKeys = {
  all: ['reviewDelegations'] as const,
  mine: () => ['reviewDelegations', 'mine'] as const,
  candidates: () => ['reviewDelegations', 'candidates'] as const,
};


/** Delegations the caller granted, plus those granted to them (#622). */
export async function listMyReviewDelegations(): Promise<MyReviewDelegations> {
  const { data } = await apiClient.get<MyReviewDelegations>(BASE);
  return data;
}

/**
 * Colleagues the caller may delegate to. Separate from the admin user listing, which is gated on
 * USER_MANAGE — a permission most reviewers do not hold.
 */
export async function listDelegateCandidates(): Promise<DelegateCandidate[]> {
  const { data } = await apiClient.get<DelegateCandidate[]>(`${BASE}/candidates`);
  return data;
}

export async function createReviewDelegation(
  input: CreateReviewDelegationInput,
): Promise<ReviewDelegation> {
  const { data } = await apiClient.post<ReviewDelegation>(BASE, {
    delegate_user_id: input.delegateUserId,
    scope_kind: input.scopeKind ?? null,
    scope_id: input.scopeId ?? null,
    reason: input.reason ?? null,
    starts_at: input.startsAt,
    ends_at: input.endsAt,
  });
  return data;
}

/** Soft revoke — the row survives as evidence for decisions already taken under it. */
export async function revokeReviewDelegation(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
