import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { AuthUser } from '@/api/auth';
import { SYSTEM_ROLE_PERMISSIONS } from '@/mocks/systemRolePermissions';

const {
  listPendingReviewsMock,
  listPendingApiReviewsMock,
  listDeploymentReviewsMock,
  listDeploymentRollbackReviewsMock,
} = vi.hoisted(() => ({
  listPendingReviewsMock: vi.fn(),
  listPendingApiReviewsMock: vi.fn(),
  listDeploymentReviewsMock: vi.fn(),
  listDeploymentRollbackReviewsMock: vi.fn(),
}));

vi.mock('@/api/reviews', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviews')>('@/api/reviews');
  return { ...actual, listPendingReviews: listPendingReviewsMock };
});
vi.mock('@/api/apiRequests', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiRequests')>('@/api/apiRequests');
  return { ...actual, listPendingApiReviews: listPendingApiReviewsMock };
});
vi.mock('@/api/deploymentReviews', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/deploymentReviews')>('@/api/deploymentReviews');
  return {
    ...actual,
    listDeploymentReviews: listDeploymentReviewsMock,
    listDeploymentRollbackReviews: listDeploymentRollbackReviewsMock,
  };
});

const { useAuthStore } = await import('@/store/authStore');
const { PENDING_COUNT_FILTERS, usePendingReviewCounts } = await import('../usePendingReviewCounts');
const { reviewKeys } = await import('@/api/reviews');
const { apiRequestKeys } = await import('@/api/apiRequests');
const { deploymentReviewKeys, deploymentRollbackReviewKeys } = await import(
  '@/api/deploymentReviews'
);

function user(permissions: string[]): AuthUser {
  return {
    id: 'u-1',
    email: 'u@example.com',
    display_name: 'U',
    role: 'REVIEWER',
    role_id: null,
    permissions,
    auth_provider: 'LOCAL',
    totp_enabled: false,
    platform_admin: false,
    preferred_language: null,
  };
}

function page(total: number) {
  return { content: [], page: 0, size: 1, total_elements: total, total_pages: total > 0 ? 1 : 0 };
}

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, ...renderHook(() => usePendingReviewCounts(), { wrapper }) };
}

describe('usePendingReviewCounts (#772)', () => {
  beforeEach(() => {
    listPendingReviewsMock.mockReset().mockResolvedValue(page(3));
    listPendingApiReviewsMock.mockReset().mockResolvedValue(page(2));
    listDeploymentReviewsMock.mockReset().mockResolvedValue(page(1));
    listDeploymentRollbackReviewsMock.mockReset().mockResolvedValue(page(4));
  });

  it('reports zeros and probes nothing for a user with no review permission', async () => {
    useAuthStore.setState({ user: user(['QUERY_SUBMIT_SELECT']), accessToken: 't' });
    const { result } = setup();
    await Promise.resolve();
    expect(result.current).toEqual({ queries: 0, api: 0, deployments: 0, rollbacks: 0, total: 0 });
    expect(listPendingReviewsMock).not.toHaveBeenCalled();
    expect(listPendingApiReviewsMock).not.toHaveBeenCalled();
    expect(listDeploymentReviewsMock).not.toHaveBeenCalled();
    expect(listDeploymentRollbackReviewsMock).not.toHaveBeenCalled();
  });

  it('probes every queue with a size=1 filter and sums the totals for a full reviewer', async () => {
    useAuthStore.setState({ user: user(SYSTEM_ROLE_PERMISSIONS.REVIEWER), accessToken: 't' });
    const { result } = setup();
    await waitFor(() => expect(result.current.total).toBe(10));
    expect(result.current).toEqual({ queries: 3, api: 2, deployments: 1, rollbacks: 4, total: 10 });
    expect(listPendingReviewsMock).toHaveBeenCalledWith(PENDING_COUNT_FILTERS.queries);
    expect(listPendingApiReviewsMock).toHaveBeenCalledWith(PENDING_COUNT_FILTERS.api);
    expect(listDeploymentReviewsMock).toHaveBeenCalledWith(PENDING_COUNT_FILTERS.deployments);
    expect(listDeploymentRollbackReviewsMock).toHaveBeenCalledWith(PENDING_COUNT_FILTERS.rollbacks);
    expect(PENDING_COUNT_FILTERS.rollbacks).toEqual({ status: 'PENDING_REVIEW', size: 1 });
  });

  it('only probes the deployment queues for a DEPLOYMENT_REVIEW-only user', async () => {
    useAuthStore.setState({ user: user(['DEPLOYMENT_REVIEW']), accessToken: 't' });
    const { result } = setup();
    await waitFor(() => expect(result.current.total).toBe(5));
    expect(result.current.queries).toBe(0);
    expect(result.current.api).toBe(0);
    expect(listPendingReviewsMock).not.toHaveBeenCalled();
    expect(listPendingApiReviewsMock).not.toHaveBeenCalled();
  });

  it('caches under the existing key factories so WebSocket invalidation still hits them', async () => {
    useAuthStore.setState({ user: user(SYSTEM_ROLE_PERMISSIONS.REVIEWER), accessToken: 't' });
    const { client, result } = setup();
    await waitFor(() => expect(result.current.total).toBe(10));
    const cache = client.getQueryCache();
    expect(cache.find({ queryKey: reviewKeys.pendingFor(PENDING_COUNT_FILTERS.queries) })).toBeTruthy();
    expect(cache.find({ queryKey: apiRequestKeys.reviewQueue(PENDING_COUNT_FILTERS.api) })).toBeTruthy();
    expect(
      cache.find({ queryKey: deploymentReviewKeys.list(PENDING_COUNT_FILTERS.deployments) }),
    ).toBeTruthy();
    expect(
      cache.find({ queryKey: deploymentRollbackReviewKeys.list(PENDING_COUNT_FILTERS.rollbacks) }),
    ).toBeTruthy();
    // Prefix invalidation, as the WS bridge does it, reaches the badge entries.
    expect(cache.findAll({ queryKey: ['reviews', 'pending'] })).toHaveLength(1);
    expect(cache.findAll({ queryKey: ['api-reviews', 'queue'] })).toHaveLength(1);
  });
});
