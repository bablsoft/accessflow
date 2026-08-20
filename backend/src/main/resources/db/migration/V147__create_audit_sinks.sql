-- #628: SIEM & WORM audit streaming. Admin-configurable external audit sinks that stream
-- audit_log rows to Splunk HEC, syslog/CEF, HMAC-signed HTTPS receivers, or S3 Object Lock
-- (WORM) segments. Each sink carries its own durable delivery cursor and health columns,
-- updated atomically with the row under optimistic locking.
--
-- The cursor is a (created_at, id) KEYSET, not a bare timestamp — audit_log.created_at is not
-- unique, so resuming from an instant alone either replays or skips rows (the
-- grant_usage_watermark rationale from V135). The nil cursor_id means "start of that instant";
-- the epoch default means "stream from the beginning of the log". The org-scoped keyset read
-- rides idx_audit_log_org_created_id (V26) — audit_log itself is audit-role-owned since V38,
-- so no new index on it is possible (or needed).
--
-- config follows the notification_channels convention: sensitive sub-fields are stored
-- AES-256-GCM encrypted under <key>_encrypted and masked in API responses.

CREATE TYPE audit_sink_type AS ENUM ('SPLUNK_HEC', 'SYSLOG_CEF', 'HTTPS_BATCH', 'S3_OBJECT_LOCK');

-- organization_id is a bare UUID (no FK), the V14 audit_log convention: the drain job writes
-- cursor/health from a background thread, and an FK's RI check on organizations would let that
-- write deadlock against operations holding an exclusive lock on organizations. Sinks are
-- admin-managed rows; orphan cleanup on org removal is not a real flow (orgs are disabled, not
-- deleted), matching audit_log itself.
CREATE TABLE audit_sinks (
    id                   UUID            PRIMARY KEY,
    organization_id      UUID            NOT NULL,
    name                 VARCHAR(255)    NOT NULL,
    type                 audit_sink_type NOT NULL,
    config               JSONB           NOT NULL DEFAULT '{}',
    enabled              BOOLEAN         NOT NULL DEFAULT true,
    -- durable at-least-once delivery cursor over audit_log
    cursor_created_at    TIMESTAMPTZ     NOT NULL DEFAULT 'epoch',
    cursor_id            UUID            NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    -- health / durable retry backoff
    consecutive_failures INT             NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    last_error           VARCHAR(500),
    version              BIGINT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_audit_sinks_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_audit_sinks_org ON audit_sinks (organization_id);
