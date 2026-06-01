-- AF-378: per-stage decision rows for access grant requests, mirroring
-- review_decisions for query requests. Reuses the existing `decision` PG enum.
-- The unique constraint (request, reviewer, stage) is the idempotency key for
-- DefaultAccessGrantRequestStateService.recordApprovalAndAdvance.

CREATE TABLE access_grant_decision (
    id                      UUID        PRIMARY KEY,
    access_grant_request_id UUID        NOT NULL REFERENCES access_grant_request(id) ON DELETE CASCADE,
    reviewer_id             UUID        NOT NULL REFERENCES users(id),
    decision                decision    NOT NULL,
    stage                   INTEGER     NOT NULL,
    comment                 TEXT,
    decided_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_access_grant_decision_reviewer_stage
        UNIQUE (access_grant_request_id, reviewer_id, stage)
);

CREATE INDEX idx_access_grant_decision_request ON access_grant_decision (access_grant_request_id);
