-- #693 (epic #682): deployment gate, outcome reporting & rollback follow-up reviews.
-- release_notified_at makes ScheduledDeploymentReleaseJob's "became releasable" announcement
-- one-shot per request; the partial index backs its scan. deployment_rollback_reviews mirrors
-- the break_glass_events retro-review shape but is deploygov-owned: break_glass_events'
-- UNIQUE deployment_request_id is already claimed by break-glass deploys (V153), and a
-- break-glass deployment that later rolls back must still get its follow-up review. Actor and
-- scope ids are bare UUIDs (no FK), like break_glass_events, so deleting a user or pipeline
-- never erases the review record; the request FK cascades exactly as there.

ALTER TABLE deployment_requests ADD COLUMN release_notified_at TIMESTAMPTZ;

CREATE INDEX idx_deployment_requests_release_scan
    ON deployment_requests (scheduled_for)
    WHERE status = 'APPROVED' AND release_notified_at IS NULL;

CREATE TYPE deployment_rollback_review_status AS ENUM ('PENDING_REVIEW', 'REVIEWED');

CREATE TABLE deployment_rollback_reviews (
    id                    UUID        PRIMARY KEY,
    deployment_request_id UUID        NOT NULL UNIQUE
                              REFERENCES deployment_requests(id) ON DELETE CASCADE,
    organization_id       UUID        NOT NULL,
    pipeline_id           UUID        NOT NULL,
    environment_id        UUID        NOT NULL,
    submitted_by          UUID        NOT NULL,
    outcome_detail        TEXT,
    status                deployment_rollback_review_status NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_by           UUID,
    review_comment        TEXT,
    reviewed_at           TIMESTAMPTZ,
    version_lock          BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Reviewer worklist: org-scoped, status-filtered, newest first.
CREATE INDEX idx_deployment_rollback_reviews_org_status
    ON deployment_rollback_reviews (organization_id, status, created_at DESC);
