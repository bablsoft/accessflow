-- AF-456: multi-tenant organization isolation hardening.
-- Per-org quotas are count-based and enforced at the service layer; a NULL or 0 limit
-- means "unlimited". `disabled` lets a platform admin take a whole tenant offline (its
-- users are blocked at login and at request time). `platform_admin` is an orthogonal
-- super-admin capability on a user (NOT a new role) that grants the PLATFORM_ADMIN
-- authority for the cross-org management endpoints; the user keeps their home-org role.

ALTER TABLE organizations ADD COLUMN max_datasources     INTEGER;
ALTER TABLE organizations ADD COLUMN max_users           INTEGER;
ALTER TABLE organizations ADD COLUMN max_queries_per_day INTEGER;
ALTER TABLE organizations ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE users ADD COLUMN platform_admin BOOLEAN NOT NULL DEFAULT FALSE;
