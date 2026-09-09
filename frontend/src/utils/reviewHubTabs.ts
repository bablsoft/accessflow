import type { AuthUser } from '@/api/auth';
import type { GovernanceDomain, GovernanceDomains } from '@/hooks/useGovernanceDomains';
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

/**
 * The governance domain a tab belongs to (#926), or absent for the always-on database domain.
 * Purely a discovery filter: it decides whether the tab is *offered*, never whether the viewer
 * may review — an explicit `?tab=` deep link still opens a tab whose domain is switched off.
 */
export const REVIEW_HUB_TAB_DOMAIN: Partial<Record<ReviewHubTabKey, GovernanceDomain>> = {
  api: 'apis',
  deployments: 'deployments',
  rollbacks: 'deployments',
};

/**
 * Any-of set the `/reviews` route guard and the sidebar entry are gated on. Permission only —
 * hiding a domain must never turn a reachable page into a 403.
 */
export const REVIEW_HUB_PERMISSIONS: Permission[] = Array.from(
  new Set(Object.values(REVIEW_HUB_TAB_PERMISSION)),
);

export function isReviewHubTabKey(value: string | null | undefined): value is ReviewHubTabKey {
  return (REVIEW_HUB_TAB_KEYS as readonly string[]).includes(value ?? '');
}

/** Tabs the viewer holds the permission for, ignoring the organization's domains. */
export function permittedReviewHubTabs(user: AuthUser | null | undefined): ReviewHubTabKey[] {
  return REVIEW_HUB_TAB_KEYS.filter((key) => hasPermission(user, REVIEW_HUB_TAB_PERMISSION[key]));
}

/** Tabs the viewer may see, in display order: permission AND an enabled governance domain. */
export function visibleReviewHubTabs(
  user: AuthUser | null | undefined,
  domains: GovernanceDomains,
): ReviewHubTabKey[] {
  return permittedReviewHubTabs(user).filter((key) => {
    const domain = REVIEW_HUB_TAB_DOMAIN[key];
    return domain === undefined || domains[domain];
  });
}

/**
 * The tab to show for a `?tab=` value. An explicitly requested tab wins whenever the viewer holds
 * its permission — even when its domain is off — so notification and dashboard deep links into a
 * de-emphasised queue keep working. Otherwise the first domain-visible tab, then the first
 * permitted one (an org that governs nothing still has to land its reviewers somewhere), and
 * finally `null` when the viewer may review nothing at all (the route guard sends them home
 * before this matters).
 */
export function resolveReviewHubTab(
  requested: string | null | undefined,
  user: AuthUser | null | undefined,
  domains: GovernanceDomains,
): ReviewHubTabKey | null {
  const permitted = permittedReviewHubTabs(user);
  if (isReviewHubTabKey(requested) && permitted.includes(requested)) return requested;
  const visible = visibleReviewHubTabs(user, domains);
  return visible[0] ?? permitted[0] ?? null;
}

/** The single place the hub's URL shape lives — notifications, dashboard tiles and redirects use it. */
export function reviewHubPath(tab: ReviewHubTabKey): string {
  return `/reviews?tab=${tab}`;
}
