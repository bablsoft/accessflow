import type { APIRequestContext } from '@playwright/test';
import { apiBase } from './datasources';

export interface GovernanceDomains {
  governs_apis: boolean;
  governs_deployments: boolean;
}

/**
 * The organization's two governance-domain switches (#926) — a visibility signal over the sidebar
 * sub-sections, the review-hub tabs and the dashboard widgets.
 *
 * Both calls need a Bearer token: the endpoint is gated on `PERM_SETUP_PROGRESS_VIEW` and the
 * backend authenticates only from the `Authorization` header. `page.context().request` shares the
 * browser's cookie jar, but the refresh cookie is scoped to `/api/v1/auth` and is never sent here,
 * so a context request without this header is a silent 401.
 */
export async function getGovernanceDomainsViaApi(
  request: APIRequestContext,
  token: string,
): Promise<GovernanceDomains> {
  const res = await request.get(`${apiBase()}/api/v1/admin/governance-domains`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) {
    throw new Error(`GET governance-domains failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as GovernanceDomains;
}

/** Replaces both flags. There is no partial update — the endpoint requires both booleans. */
export async function setGovernanceDomainsViaApi(
  request: APIRequestContext,
  token: string,
  domains: GovernanceDomains,
): Promise<void> {
  const res = await request.put(`${apiBase()}/api/v1/admin/governance-domains`, {
    headers: { Authorization: `Bearer ${token}` },
    data: domains,
  });
  if (!res.ok()) {
    throw new Error(`PUT governance-domains failed: ${res.status()} ${await res.text()}`);
  }
}

/**
 * Restore the suite baseline: both optional domains on, as `global-setup.ts` leaves them. Safe to
 * call after a failure, and the only correct way for a spec that toggles a domain to clean up —
 * leaving one off would strip the navigation, review tabs and dashboard widgets that later specs
 * (review-hub.spec.ts, dashboard.spec.ts) assert on.
 */
export async function resetGovernanceDomainsToBaseline(
  request: APIRequestContext,
  token: string,
): Promise<void> {
  await setGovernanceDomainsViaApi(request, token, {
    governs_apis: true,
    governs_deployments: true,
  });
}
