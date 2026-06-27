-- AF-500: API Access Governance — governed-call pipeline. Builds on the V100 connector foundation:
-- api_requests mirrors query_requests (reusing the shared query_status / submission_reason / decision
-- enum types), api_review_decisions records per-stage reviewer decisions, api_routing_policies drives
-- attribute-based routing, and ai_analyses / break_glass_events are extended to key an analysis or a
-- break-glass retro-review off an API request as well as a query request.

CREATE TYPE api_routing_action AS ENUM ('AUTO_APPROVE', 'AUTO_REJECT', 'REQUIRE_APPROVALS', 'ESCALATE');

CREATE TABLE api_requests (
    id                    UUID              PRIMARY KEY,
    connector_id          UUID              NOT NULL,
    organization_id       UUID              NOT NULL,
    submitted_by          UUID              NOT NULL,
    operation_id          TEXT,
    verb                  VARCHAR(16)       NOT NULL,
    request_path          TEXT              NOT NULL,
    request_headers       JSONB             NOT NULL DEFAULT '{}',
    request_body          TEXT,
    is_write              BOOLEAN           NOT NULL DEFAULT FALSE,
    status                query_status      NOT NULL DEFAULT 'PENDING_AI',
    submission_reason     submission_reason NOT NULL DEFAULT 'USER_SUBMITTED',
    justification         TEXT,
    ai_analysis_id        UUID,
    scheduled_for         TIMESTAMPTZ,
    required_approvals    INTEGER           NOT NULL DEFAULT 1,
    response_status_code  INTEGER,
    response_duration_ms  INTEGER,
    response_bytes        BIGINT,
    response_truncated    BOOLEAN           NOT NULL DEFAULT FALSE,
    response_snapshot     TEXT,
    error_message         TEXT,
    submitted_ip          VARCHAR(45),
    submitted_user_agent  TEXT,
    version               BIGINT            NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_requests_connector_status ON api_requests (connector_id, status);
CREATE INDEX idx_api_requests_submitted_by ON api_requests (submitted_by, created_at DESC);
CREATE INDEX idx_api_requests_org_status ON api_requests (organization_id, status, created_at DESC);
CREATE INDEX idx_api_requests_scheduled
    ON api_requests (scheduled_for) WHERE status = 'APPROVED' AND scheduled_for IS NOT NULL;
CREATE INDEX idx_api_requests_pending_review
    ON api_requests (created_at) WHERE status = 'PENDING_REVIEW';

CREATE TABLE api_review_decisions (
    id              UUID        PRIMARY KEY,
    api_request_id  UUID        NOT NULL REFERENCES api_requests(id) ON DELETE CASCADE,
    reviewer_id     UUID        NOT NULL,
    decision        decision    NOT NULL,
    comment         TEXT,
    stage           INTEGER     NOT NULL DEFAULT 1,
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_api_review_decisions_request_reviewer_stage UNIQUE (api_request_id, reviewer_id, stage)
);

CREATE INDEX idx_api_review_decisions_request ON api_review_decisions (api_request_id);

CREATE TABLE api_routing_policies (
    id                 UUID               PRIMARY KEY,
    organization_id    UUID               NOT NULL,
    connector_id       UUID,
    name               VARCHAR(255)       NOT NULL,
    conditions         JSONB              NOT NULL DEFAULT '{}',
    action             api_routing_action NOT NULL,
    required_approvals INTEGER,
    priority           INTEGER            NOT NULL DEFAULT 100,
    enabled            BOOLEAN            NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_routing_policies_org
    ON api_routing_policies (organization_id, enabled, priority);

-- Extend ai_analyses to key off either a query request or an API request (exactly one), keeping the
-- per-org token-budget accounting in DefaultAiRateLimiter unified.
ALTER TABLE ai_analyses ALTER COLUMN query_request_id DROP NOT NULL;
ALTER TABLE ai_analyses ADD COLUMN api_request_id UUID REFERENCES api_requests(id);
ALTER TABLE ai_analyses ADD CONSTRAINT chk_ai_analyses_target
    CHECK (num_nonnulls(query_request_id, api_request_id) = 1);

-- Reuse break_glass_events for API break-glass retro-review (mirror AF-385). datasource_id becomes
-- optional since an API break-glass targets a connector, not a datasource.
ALTER TABLE break_glass_events ALTER COLUMN query_request_id DROP NOT NULL;
ALTER TABLE break_glass_events ALTER COLUMN datasource_id DROP NOT NULL;
ALTER TABLE break_glass_events ADD COLUMN api_request_id UUID UNIQUE REFERENCES api_requests(id) ON DELETE CASCADE;
ALTER TABLE break_glass_events ADD COLUMN connector_id UUID;
ALTER TABLE break_glass_events ADD CONSTRAINT chk_break_glass_target
    CHECK (num_nonnulls(query_request_id, api_request_id) = 1);
