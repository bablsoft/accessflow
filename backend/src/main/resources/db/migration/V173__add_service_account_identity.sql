-- #868 (epic #867): service-account identity model. A service account becomes a typed identity —
-- a principal_type discriminator on users plus a 1:1 service_accounts detail row — instead of an
-- indistinguishable users row. Deliberately NOT a separate identity table: every actor FK in the
-- system points at users(id) and the self-approval invariant is a UUID.equals on it, so a parallel
-- identity would force polymorphic actor columns everywhere.
--
-- Nothing enforces the discriminator yet (sign-in blocking is #869, MCP allow-lists #872, rate
-- limits #873, admin CRUD #871); this migration only has to type the rows that already exist and
-- leave ddl-auto=validate satisfied.

CREATE TYPE principal_type AS ENUM ('HUMAN', 'SERVICE_ACCOUNT');

-- DEFAULT 'HUMAN' so every pre-existing row (and any row an old pod still inserts during a rolling
-- deploy) is a person; the backfill below flips exactly the bootstrap-declared accounts.
ALTER TABLE users ADD COLUMN principal_type principal_type NOT NULL DEFAULT 'HUMAN';

-- Which surface owns the account's declared fields: BOOTSTRAP accounts come from the reconciler's
-- YAML and re-assert email/display name/role/declared key on every restart; UI accounts (#871)
-- are edited from the admin pages only.
CREATE TYPE service_account_source AS ENUM ('UI', 'BOOTSTRAP');

-- The detail row IS part of the identity, so these are real FKs (the api_keys precedent), not the
-- bare cross-module UUIDs other modules use: deleting the user deletes the account, deleting the
-- owning human only detaches ownership.
CREATE TABLE service_accounts (
    user_id               UUID                   PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    organization_id       UUID                   NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    description           VARCHAR(500),
    owner_user_id         UUID                   REFERENCES users(id) ON DELETE SET NULL,
    managed_by            service_account_source NOT NULL,
    -- MCP tool allow-list (#872): NULL = every tool, '{}' = no tool. Tool names, not an enum
    -- type, so a renamed tool never makes an existing row unreadable.
    mcp_tool_allow_list   TEXT[],
    -- Per-identity API-key throttles (#873); NULL = unlimited.
    rate_limit_per_minute INTEGER,
    rate_limit_per_day    INTEGER,
    version               BIGINT                 NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ            NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_accounts_org ON service_accounts (organization_id);

-- Backfill. BootstrapStateTracker records every reconciler-created service account as a
-- bootstrap_state row with resource_type = 'SERVICE_ACCOUNT' and resource_id = users.id, so the
-- set is exactly identifiable. EXISTS rather than a JOIN: bootstrap_state's uniqueness key includes
-- organization_id, so the same resource_id recorded under two organizations would make a JOIN
-- yield the user twice; EXISTS is duplicate-proof by construction.
UPDATE users u
   SET principal_type = 'SERVICE_ACCOUNT'
 WHERE EXISTS (SELECT 1 FROM bootstrap_state b
                WHERE b.resource_type = 'SERVICE_ACCOUNT' AND b.resource_id = u.id);

INSERT INTO service_accounts (user_id, organization_id, managed_by)
SELECT u.id, u.organization_id, 'BOOTSTRAP'
  FROM users u
 WHERE EXISTS (SELECT 1 FROM bootstrap_state b
                WHERE b.resource_type = 'SERVICE_ACCOUNT' AND b.resource_id = u.id);
