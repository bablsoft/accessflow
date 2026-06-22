-- AF-385: break-glass / emergency access. Each break-glass execution opens a MANDATORY retro-review
-- task tracked here, alongside the executed query (which lands in its normal terminal EXECUTED/FAILED
-- state — this row never re-opens it). An admin must reconcile/acknowledge it after the fact; the
-- admin "Break-glass log" lists PENDING_REVIEW rows. submitted_by / reviewed_by / organization_id /
-- datasource_id are bare UUIDs (no FK), like query_snapshots / audit_log, so deleting a user never
-- erases the record. query_request_id keeps a real FK + UNIQUE as the one-event-per-query idempotency
-- backstop.

CREATE TYPE break_glass_status AS ENUM ('PENDING_REVIEW', 'REVIEWED');

CREATE TABLE break_glass_events (
    id               UUID               PRIMARY KEY,
    query_request_id UUID               NOT NULL UNIQUE REFERENCES query_requests(id) ON DELETE CASCADE,
    organization_id  UUID               NOT NULL,
    datasource_id    UUID               NOT NULL,
    submitted_by     UUID               NOT NULL,
    justification    TEXT               NOT NULL,
    status           break_glass_status NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_by      UUID,
    review_comment   TEXT,
    reviewed_at      TIMESTAMPTZ,
    version          BIGINT             NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Admin "Break-glass log": org-scoped, status-filtered (PENDING_REVIEW = unreconciled), newest first.
CREATE INDEX idx_break_glass_events_org_status
    ON break_glass_events (organization_id, status, created_at DESC);
