import type { AuthUser } from '@/api/auth';
import { hasPermission, type Permission } from '@/utils/permissions';

/**
 * The unified review queue (#772): one `/reviews` page, one tab per request kind. Adding a queue
 * is one entry here plus one in `ReviewHubPage`'s registry — the sidebar badge, the `?tab=`
 * routing and the permission gating all derive from this list.
 */
export const REVIEW_HUB_TAB_KEYS = ['queries', 'api', 'deployments', 'rollbacks'] as const;

export type ReviewHubTabKey = (typeof REVIEW_HUB_TAB_KEYS)[number];

/** The functional permission that makes a tab visible. A tab the viewer lacks is never rendered. */
export const REVIEW_HUB_TAB_PERMISSION: Record<ReviewHubTabKey, Permission> = {
  queries: 'QUERY_REVIEW',
  api: 'API_REQUEST_REVIEW',
  deployments: 'DEPLOYMENT_REVIEW',
  rollbacks: 'DEPLOYMENT_REVIEW',
};

/** Any-of set the `/reviews` route guard and the sidebar entry are gated on. */
export const REVIEW_HUB_PERMISSIONS: Permission[] = Array.from(
  new Set(Object.values(REVIEW_HUB_TAB_PERMISSION)),
);

export function isReviewHubTabKey(value: string | null | undefined): value is ReviewHubTabKey {
  return (REVIEW_HUB_TAB_KEYS as readonly string[]).includes(value ?? '');
}

/** Tabs the viewer may see, in display order. */
export function visibleReviewHubTabs(user: AuthUser | null | undefined): ReviewHubTabKey[] {
  return REVIEW_HUB_TAB_KEYS.filter((key) => hasPermission(user, REVIEW_HUB_TAB_PERMISSION[key]));
}

/**
 * The tab to show for a `?tab=` value: the requested one when the viewer may see it, otherwise
 * the first visible tab, or `null` when the viewer may review nothing (the route guard sends
 * them home before this matters).
 */
export function resolveReviewHubTab(
  requested: string | null | undefined,
  user: AuthUser | null | undefined,
): ReviewHubTabKey | null {
  const visible = visibleReviewHubTabs(user);
  if (isReviewHubTabKey(requested) && visible.includes(requested)) return requested;
  return visible[0] ?? null;
}

/** The single place the hub's URL shape lives — notifications, dashboard tiles and redirects use it. */
export function reviewHubPath(tab: ReviewHubTabKey): string {
  return `/reviews?tab=${tab}`;
}
