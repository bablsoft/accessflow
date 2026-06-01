-- AF-378: just-in-time (JIT) time-bound access requests. A user self-requests
-- temporary, scoped datasource access (read/write/DDL, optional schema/table
-- scope) for an ISO-8601 duration. The request flows through the same reviewer
-- eligibility + multi-stage approval machinery as query review; on final-stage
-- approval the access module materialises a time-boxed datasource_user_permissions
-- row (expires_at = now + requested_duration) and stores its id in
-- granted_permission_id. AccessGrantExpiryJob revokes the permission on expiry.

CREATE TYPE access_grant_status AS ENUM
    ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'REVOKED', 'CANCELLED');

CREATE TABLE access_grant_request (
    id                    UUID                PRIMARY KEY,
    organization_id       UUID                NOT NULL REFERENCES organizations(id),
    requester_id          UUID                NOT NULL REFERENCES users(id),
    datasource_id         UUID                NOT NULL REFERENCES datasources(id),
    can_read              BOOLEAN             NOT NULL DEFAULT false,
    can_write             BOOLEAN             NOT NULL DEFAULT false,
    can_ddl               BOOLEAN             NOT NULL DEFAULT false,
    allowed_schemas       TEXT[],
    allowed_tables        TEXT[],
    requested_duration    TEXT                NOT NULL,
    justification         TEXT,
    status                access_grant_status NOT NULL DEFAULT 'PENDING',
    expires_at            TIMESTAMPTZ,
    -- Bare UUID (no FK): the granted datasource_user_permissions row is hard-deleted
    -- on revoke/expiry, so an FK would either block the delete or null this column
    -- unexpectedly. Mirrors the ai_analysis_id convention on query_requests.
    granted_permission_id UUID,
    version               BIGINT              NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_access_grant_request_capability
        CHECK (can_read OR can_write OR can_ddl)
);

CREATE INDEX idx_access_grant_request_org_status  ON access_grant_request (organization_id, status);
CREATE INDEX idx_access_grant_request_requester   ON access_grant_request (requester_id, created_at DESC);
CREATE INDEX idx_access_grant_request_datasource  ON access_grant_request (datasource_id);
-- Partial index backing the AccessGrantExpiryJob scan (APPROVED rows past expires_at).
CREATE INDEX idx_access_grant_request_expiry      ON access_grant_request (expires_at)
    WHERE status = 'APPROVED';
