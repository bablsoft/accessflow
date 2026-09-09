import { useMemo } from 'react';
import type { AuthUser } from '@/api/auth';
import { useAuthStore } from '@/store/authStore';

/** The two governance domains an organization can switch off (AF-898). */
export type GovernanceDomain = 'apis' | 'deployments';

export type GovernanceDomains = Record<GovernanceDomain, boolean>;

/**
 * Which governance domains the signed-in user's organization has opted into.
 *
 * Fails open on purpose: a session issued before #926 — or any payload that omits a flag —
 * reads as `true`, so an older token renders exactly the navigation it did before rather than
 * losing whole sections. Only an explicit `false` from the server hides anything.
 *
 * This is a **visibility** signal, never an entitlement. It decides which sidebar sub-sections,
 * review-hub tabs and dashboard widgets are offered; routes stay registered, `AuthGuard`
 * permission checks are untouched, and every deep link keeps working.
 */
export function governanceDomainsOf(user: AuthUser | null | undefined): GovernanceDomains {
  return {
    apis: user?.governs_apis ?? true,
    deployments: user?.governs_deployments ?? true,
  };
}

/**
 * The shared read over the auth store — no per-component fetch, no prop drilling.
 *
 * Memoized on the two booleans rather than on the user object: `governanceDomainsOf` builds a
 * fresh record every call, and callers put the result straight into `useMemo`/`useEffect`
 * dependency arrays, where a new identity each render would silently defeat them.
 */
export function useGovernanceDomains(): GovernanceDomains {
  const governsApis = useAuthStore((s) => s.user?.governs_apis);
  const governsDeployments = useAuthStore((s) => s.user?.governs_deployments);
  return useMemo(
    () => ({ apis: governsApis ?? true, deployments: governsDeployments ?? true }),
    [governsApis, governsDeployments],
  );
}
