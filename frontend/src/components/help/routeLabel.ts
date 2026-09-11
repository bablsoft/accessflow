/**
 * Maps the current route to a human **label** for the help chat request (epic #899 decision 3).
 *
 * The panel must never send `useLocation().pathname`: the label lands in the system message of a
 * prompt sent to a third-party model, and every AccessFlow detail route carries an id
 * (`/queries/<uuid>`, `/datasources/<uuid>/settings`). The server sanitizes defensively, but a
 * dropped label costs the model its only orientation hint — so the mapping happens here, from a
 * fixed table, and the pathname itself is never interpolated into the result.
 *
 * Unknown routes map to nothing rather than to a guess: no label is a supported request.
 */

/**
 * Route prefix → the sidebar's own i18n key, longest prefix first. Reusing `nav.*` rather than
 * inventing a parallel set of labels means the model is told the same screen name the user is
 * looking at, in the user's own language, and the mapping cannot drift out of translation.
 *
 * A prefix matches the whole pathname, or a pathname whose next character is `/`, so `/queries`
 * never matches `/queries-foo`.
 */
const ROUTE_LABEL_KEYS: ReadonlyArray<readonly [string, string]> = [
  ['/admin/deployment-pipelines', 'nav.deploymentPipelines'],
  ['/admin/data-classifications', 'nav.data_classifications'],
  ['/admin/over-provisioned-access', 'nav.over_provisioned_access'],
  ['/admin/privileged-access', 'nav.privileged_access'],
  ['/admin/lifecycle/policies', 'nav.lifecycle'],
  ['/admin/access-requests', 'nav.access_requests'],
  ['/admin/datasource-health', 'nav.datasource_health'],
  ['/admin/routing-policies', 'nav.routing_policies'],
  ['/admin/organizations', 'nav.organizations'],
  ['/admin/notifications', 'nav.notifications'],
  ['/admin/review-plans', 'nav.review_plans'],
  ['/admin/ai-analyses', 'nav.ai_analyses'],
  ['/admin/help-agent', 'nav.help_agent'],
  ['/admin/attestation', 'nav.attestation'],
  ['/admin/break-glass', 'nav.break_glass'],
  ['/admin/audit-sinks', 'nav.audit_sinks'],
  ['/admin/ai-configs', 'nav.ai_configs'],
  ['/admin/connectors', 'nav.connectors'],
  ['/admin/audit-log', 'nav.audit'],
  ['/admin/languages', 'nav.languages'],
  ['/admin/anomalies', 'nav.anomalies'],
  ['/admin/langfuse', 'nav.langfuse'],
  ['/admin/auditor', 'nav.auditor'],
  ['/admin/drivers', 'nav.custom_drivers'],
  ['/admin/oauth2', 'nav.oauth2'],
  ['/admin/groups', 'nav.groups'],
  ['/admin/users', 'nav.users'],
  ['/admin/roles', 'nav.roles'],
  ['/admin/saml', 'nav.saml'],
  ['/admin/scim', 'nav.scim'],
  ['/admin/slack', 'nav.slack'],
  ['/lifecycle/erasure-reviews', 'nav.erasure_review'],
  ['/lifecycle/erasure', 'nav.request_erasure'],
  ['/request-groups/reviews', 'nav.requestGroupReviews'],
  ['/request-groups', 'nav.requestGroups'],
  ['/deployment-versions', 'nav.deploymentVersions'],
  ['/api-connectors', 'nav.apiConnectors'],
  ['/access-requests', 'nav.request_access'],
  ['/reviews/attestations', 'nav.attestation_reviews'],
  ['/api-requests', 'nav.apiRequests'],
  ['/deployments', 'nav.deployments'],
  ['/datasources', 'nav.datasources'],
  ['/api-editor', 'nav.apiEditor'],
  ['/dashboard', 'nav.dashboard'],
  ['/profile', 'nav.profile'],
  ['/reviews', 'nav.reviews'],
  ['/queries', 'nav.queries'],
  ['/editor', 'nav.editor'],
];

/** The i18n key for a pathname, or `null` when the route is not one we name. */
export function routeLabelKey(pathname: string): string | null {
  const path = normalize(pathname);
  for (const [prefix, key] of ROUTE_LABEL_KEYS) {
    if (path === prefix || path.startsWith(`${prefix}/`)) {
      return key;
    }
  }
  return null;
}

/**
 * The label to send, or `undefined` for an unmapped route. `translate` is the caller's `t()`, so
 * the label reaches the model in the user's own language — the same language the answer comes back
 * in.
 */
export function routeLabel(
  pathname: string,
  translate: (key: string) => string,
): string | undefined {
  const key = routeLabelKey(pathname);
  return key === null ? undefined : translate(key);
}

/** Drops a trailing slash so `/queries/` and `/queries` are the same route. */
function normalize(pathname: string): string {
  if (pathname.length > 1 && pathname.endsWith('/')) {
    return pathname.slice(0, -1);
  }
  return pathname;
}
