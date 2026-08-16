import { describe, expect, it, vi, beforeEach } from 'vitest';
import { apiClient } from './client';
import {
  createReviewDelegation,
  listDelegateCandidates,
  listMyReviewDelegations,
  reviewDelegationKeys,
  revokeReviewDelegation,
} from './reviewDelegations';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

const mockedGet = vi.mocked(apiClient.get);
const mockedPost = vi.mocked(apiClient.post);
const mockedDelete = vi.mocked(apiClient.delete);

describe('reviewDelegations api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('exposes hierarchical query keys', () => {
    expect(reviewDelegationKeys.mine()).toEqual(['reviewDelegations', 'mine']);
    expect(reviewDelegationKeys.candidates()).toEqual(['reviewDelegations', 'candidates']);
  });

  it('reads both directions from /me/review-delegations', async () => {
    mockedGet.mockResolvedValueOnce({ data: { granted: [], received: [] } } as never);

    await expect(listMyReviewDelegations()).resolves.toEqual({ granted: [], received: [] });
    expect(mockedGet.mock.calls[0]?.[0]).toBe('/api/v1/me/review-delegations');
  });

  it('reads delegate candidates from the dedicated endpoint', async () => {
    mockedGet.mockResolvedValueOnce({ data: [] } as never);

    await listDelegateCandidates();

    expect(mockedGet.mock.calls[0]?.[0]).toBe('/api/v1/me/review-delegations/candidates');
  });

  it('maps camelCase input onto the snake_case wire body', async () => {
    mockedPost.mockResolvedValueOnce({ data: { id: 'd1' } } as never);

    await createReviewDelegation({
      delegateUserId: 'u1',
      scopeKind: 'DATASOURCE',
      scopeId: 'ds1',
      reason: 'Leave',
      startsAt: '2026-08-20T00:00:00Z',
      endsAt: '2026-08-30T00:00:00Z',
    });

    expect(mockedPost.mock.calls[0]?.[1]).toEqual({
      delegate_user_id: 'u1',
      scope_kind: 'DATASOURCE',
      scope_id: 'ds1',
      reason: 'Leave',
      starts_at: '2026-08-20T00:00:00Z',
      ends_at: '2026-08-30T00:00:00Z',
    });
  });

  it('sends explicit nulls for an unscoped delegation', async () => {
    mockedPost.mockResolvedValueOnce({ data: { id: 'd1' } } as never);

    await createReviewDelegation({
      delegateUserId: 'u1',
      startsAt: '2026-08-20T00:00:00Z',
      endsAt: '2026-08-30T00:00:00Z',
    });

    const body = mockedPost.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body.scope_kind).toBeNull();
    expect(body.scope_id).toBeNull();
  });

  it('revokes by id', async () => {
    mockedDelete.mockResolvedValueOnce({ data: undefined } as never);

    await revokeReviewDelegation('d1');

    expect(mockedDelete.mock.calls[0]?.[0]).toBe('/api/v1/me/review-delegations/d1');
  });
});
