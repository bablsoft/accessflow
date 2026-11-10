-- #1023 (epic #1022): storage foundation for post-deployment verification. An environment's
-- verification config is one nullable JSONB blob (NULL = verification off) that is snapshotted onto
-- the request when a verification starts, so a later edit never changes a running verification.
-- Samples land in deployment_verifications, one row per check per sample. Nothing reads or writes
-- these columns yet — the evaluator, job and transitions arrive with the later sub-issues.

CREATE TYPE verification_status AS ENUM ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'SKIPPED');

CREATE TYPE verification_check_kind AS ENUM ('HTTP_HEALTH', 'PROMQL_THRESHOLD');

ALTER TABLE deployment_environments ADD COLUMN verification JSONB;

ALTER TABLE deployment_requests
    ADD COLUMN verification_status      verification_status NOT NULL DEFAULT 'PENDING',
    ADD COLUMN verification_config      JSONB,
    ADD COLUMN verification_started_at  TIMESTAMPTZ,
    ADD COLUMN verification_finished_at TIMESTAMPTZ,
    ADD COLUMN verification_detail      TEXT,
    ADD COLUMN last_verified_at         TIMESTAMPTZ;

-- A request that already reported an outcome can never be verified; every other row stays
-- PENDING (only RUNNING rows are scanned, so never-executed PENDING rows are harmless).
UPDATE deployment_requests
   SET verification_status = 'SKIPPED'::verification_status
 WHERE outcome IS NOT NULL;

-- Backs the verification sampling scan.
CREATE INDEX idx_deployment_requests_verification_scan
    ON deployment_requests (last_verified_at)
    WHERE status = 'EXECUTED' AND verification_status = 'RUNNING';

CREATE TABLE deployment_verifications (
    id                    UUID                    PRIMARY KEY,
    deployment_request_id UUID                    NOT NULL
        REFERENCES deployment_requests (id) ON DELETE CASCADE,
    organization_id       UUID                    NOT NULL,
    check_name            VARCHAR(64)             NOT NULL,
    check_kind            verification_check_kind NOT NULL,
    sampled_at            TIMESTAMPTZ             NOT NULL,
    passed                BOOLEAN                 NOT NULL,
    observed_value        TEXT,
    detail                TEXT
);

CREATE INDEX idx_deployment_verifications_request_sampled
    ON deployment_verifications (deployment_request_id, sampled_at);
