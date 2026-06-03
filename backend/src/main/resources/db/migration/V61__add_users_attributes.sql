-- AF-380: admin-editable per-user attribute map, resolvable in row-security predicate
-- templates as :user.<key> (alongside the built-ins user.id / user.email / user.role /
-- user.groups). Values are set by admins through the user admin API — NOT synced from the
-- IdP — so air-gapped and SSO installs alike can drive row predicates off arbitrary
-- per-user attributes (e.g. region, tenant). Stored as JSONB; defaults to an empty object
-- so existing rows need no backfill.

ALTER TABLE users ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}';
