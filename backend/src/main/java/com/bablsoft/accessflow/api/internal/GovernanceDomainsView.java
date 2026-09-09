package com.bablsoft.accessflow.api.internal;

/**
 * Which governance domains the caller's organization has opted into (AF-898). Database access
 * governance is always on and has no flag. These two are a <em>visibility</em> signal only: they
 * decide which onboarding steps, sidebar sub-sections, review-hub tabs and dashboard widgets the
 * frontend offers (#926), and never gate a route, a permission or an endpoint's authorization.
 */
public record GovernanceDomainsView(boolean governsApis, boolean governsDeployments) {
}
