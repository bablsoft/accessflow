import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listPendingReviews, reviewKeys, type PendingReviewsFilters } from '@/api/reviews';
import { listPendingApiReviews, apiRequestKeys, type ApiReviewListFilters } from '@/api/apiRequests';
import {
  deploymentReviewKeys,
  deploymentRollbackReviewKeys,
  listDeploymentReviews,
  listDeploymentRollbackReviews,
  type DeploymentReviewListFilters,
  type DeploymentRollbackReviewListFilters,
} from '@/api/deploymentReviews';
import { useAuthStore } from '@/store/authStore';
import { hasPermission } from '@/utils/permissions';
import { REVIEW_HUB_TAB_PERMISSION, type ReviewHubTabKey } from '@/utils/reviewHubTabs';

export type PendingReviewCounts = Record<ReviewHubTabKey, number> & { total: number };

/**
 * One `size=1` probe per queue: every list envelope carries `total_elements`, so the count comes
 * for free without a dedicated endpoint. The filter objects are module constants so the sidebar
 * badge (`AppLayout`) and the hub's tab labels hash to the *same* cache entries and dedupe into
 * one fetch per queue.
 */
export const PENDING_COUNT_FILTERS = {
  queries: { size: 1 } satisfies PendingReviewsFilters,
  api: { size: 1 } satisfies ApiReviewListFilters,
  deployments: { size: 1 } satisfies DeploymentReviewListFilters,
  rollbacks: { status: 'PENDING_REVIEW', size: 1 } satisfies DeploymentRollbackReviewListFilters,
} as const;

// Polling backstop for missed WebSocket frames — the WS bridge invalidates the same keys on
// `review.*`, `deployment.status_changed` and the reviewer-targeted `notification.created` events,
// so this only matters when a frame was dropped.
const REFETCH_INTERVAL_MS = 30_000;

/**
 * Pending counts for every review queue the current user may work (#772). A queue the user lacks
 * the permission for is never fetched and reports `0`.
 */
export function usePendingReviewCounts(): PendingReviewCounts {
  const user = useAuthStore((s) => s.user);
  const may = (tab: ReviewHubTabKey) => !!user && hasPermission(user, REVIEW_HUB_TAB_PERMISSION[tab]);

  const queries = useQuery({
    queryKey: reviewKeys.pendingFor(PENDING_COUNT_FILTERS.queries),
    queryFn: () => listPendingReviews(PENDING_COUNT_FILTERS.queries),
    enabled: may('queries'),
    refetchInterval: REFETCH_INTERVAL_MS,
  });
  const api = useQuery({
    queryKey: apiRequestKeys.reviewQueue(PENDING_COUNT_FILTERS.api),
    queryFn: () => listPendingApiReviews(PENDING_COUNT_FILTERS.api),
    enabled: may('api'),
    refetchInterval: REFETCH_INTERVAL_MS,
  });
  const deployments = useQuery({
    queryKey: deploymentReviewKeys.list(PENDING_COUNT_FILTERS.deployments),
    queryFn: () => listDeploymentReviews(PENDING_COUNT_FILTERS.deployments),
    enabled: may('deployments'),
    refetchInterval: REFETCH_INTERVAL_MS,
  });
  const rollbacks = useQuery({
    queryKey: deploymentRollbackReviewKeys.list(PENDING_COUNT_FILTERS.rollbacks),
    queryFn: () => listDeploymentRollbackReviews(PENDING_COUNT_FILTERS.rollbacks),
    enabled: may('rollbacks'),
    refetchInterval: REFETCH_INTERVAL_MS,
  });

  const q = queries.data?.total_elements ?? 0;
  const a = api.data?.total_elements ?? 0;
  const d = deployments.data?.total_elements ?? 0;
  const r = rollbacks.data?.total_elements ?? 0;

  return useMemo(
    () => ({ queries: q, api: a, deployments: d, rollbacks: r, total: q + a + d + r }),
    [q, a, d, r],
  );
}
