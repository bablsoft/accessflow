// Public docs site: https://accessflow.bablsoft.com/ is the site root, /docs/ the docs page.
// Deliberately a hardcoded constant rather than runtime-overridable: the docs deploy with the
// marketing site, not per-install, so there is nothing an operator would repoint.
export const DOCS_BASE_URL = 'https://accessflow.bablsoft.com/docs/';

/**
 * Every anchor in website/docs/index.html an admin page may deep-link to.
 * Adding a page? Add its anchor here AND ship the matching `id` in
 * website/docs/index.html — config/__tests__/docs.test.ts enforces both.
 */
export const DOCS_ANCHORS = [
  'cfg-access-requests',
  'cfg-ai',
  'cfg-ai-analyses',
  'cfg-anomalies',
  'cfg-api-connectors',
  'cfg-attestation',
  'cfg-audit-log',
  'cfg-break-glass',
  'cfg-connectors',
  'cfg-data-classifications',
  'cfg-datasource-health',
  'cfg-datasources',
  'cfg-drivers',
  'cfg-groups',
  'cfg-langfuse',
  'cfg-languages',
  'cfg-lifecycle',
  'cfg-notification-channels',
  'cfg-oauth',
  'cfg-organizations',
  'cfg-review-plans',
  'cfg-roles',
  'cfg-routing-policies',
  'cfg-saml',
  'cfg-slack',
  'cfg-users',
  'compliance-reports',
] as const;

export type DocsAnchor = (typeof DOCS_ANCHORS)[number];

export const docsUrl = (anchor: DocsAnchor): string => `${DOCS_BASE_URL}#${anchor}`;
