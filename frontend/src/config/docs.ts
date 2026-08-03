// Public docs site: https://accessflow.bablsoft.com/ is the site root, /docs/ the docs hub.
// Deliberately a hardcoded constant rather than runtime-overridable: the docs deploy with the
// marketing site, not per-install, so there is nothing an operator would repoint.
export const DOCS_BASE_URL = 'https://accessflow.bablsoft.com/docs/';

/**
 * Every anchor in the public docs an admin page may deep-link to, mapped to the
 * chapter page that owns it.
 *
 * The docs used to be one long page, so every anchor lived at `/docs/#<anchor>`.
 * They are now split across per-chapter URLs (`/docs/configuration/auth/#cfg-saml`).
 * `website/app.js` keeps a permanent forwarder for the old one-page form — already
 * released self-hosted frontends still emit it and never update — but new links
 * should point straight at the chapter so they cost no redirect.
 *
 * Adding a section? Add its anchor here AND ship the matching `id` in that chapter
 * file under website/docs/ — config/__tests__/docs.test.ts enforces both halves.
 */
export const DOCS_ANCHOR_PAGES = {
  'cfg-organizations': 'configuration/users-roles/',
  'cfg-languages': 'configuration/users-roles/',
  'cfg-users': 'configuration/users-roles/',
  'cfg-roles': 'configuration/users-roles/',
  'cfg-access-requests': 'configuration/users-roles/',
  'cfg-break-glass': 'configuration/users-roles/',
  'cfg-groups': 'configuration/users-roles/',
  'cfg-datasources': 'configuration/datasources/',
  'cfg-data-classifications': 'configuration/datasources/',
  'cfg-drivers': 'configuration/datasources/',
  'cfg-datasource-health': 'configuration/datasources/',
  'cfg-connectors': 'configuration/connectors/',
  'cfg-api-connectors': 'configuration/connectors/',
  'cfg-review-plans': 'configuration/review-workflows/',
  'cfg-routing-policies': 'configuration/review-workflows/',
  'cfg-attestation': 'configuration/review-workflows/',
  'cfg-ai': 'configuration/ai/',
  'cfg-ai-analyses': 'configuration/ai/',
  'cfg-anomalies': 'configuration/ai/',
  'cfg-langfuse': 'configuration/ai/',
  'cfg-oauth': 'configuration/auth/',
  'cfg-saml': 'configuration/auth/',
  'cfg-notification-channels': 'configuration/notifications/',
  'cfg-slack': 'configuration/notifications/',
  'cfg-smtp': 'configuration/notifications/',
  'cfg-audit-log': 'configuration/audit-compliance/',
  'compliance-reports': 'configuration/audit-compliance/',
  'cfg-lifecycle': 'configuration/audit-compliance/',
} as const;

export type DocsAnchor = keyof typeof DOCS_ANCHOR_PAGES;

export const DOCS_ANCHORS = Object.keys(DOCS_ANCHOR_PAGES) as readonly DocsAnchor[];

export const docsUrl = (anchor: DocsAnchor): string =>
  `${DOCS_BASE_URL}${DOCS_ANCHOR_PAGES[anchor]}#${anchor}`;
