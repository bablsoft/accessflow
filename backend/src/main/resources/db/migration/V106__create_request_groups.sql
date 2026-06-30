-- AF-501: Request chaining & grouping. A new `requestgroups` module lets a user bundle several
-- members — each either a database query (against possibly different datasources) or an AF-500
-- governed API call — into one grouped request, reviewed & approved as a single element, then
-- executed as an ordered sequence (no distributed rollback: already-applied members stay).
--
-- Group items are SELF-CONTAINED (inline SQL / inline API-call); they do NOT create query_requests
-- or api_requests rows. The group is the unit of AI + review + approval; a group executor drives the
-- members through the low-level proxy QueryExecutor (queries) and the apigov inline-execution entry
-- point (API calls).
--
-- Cross-module references (organization_id / submitted_by / datasource_id / api_connector_id /
-- reviewer_id) are bare UUIDs (no FK), like api_requests / audit_log / break_glass_events, so the
-- group rows survive after the referenced aggregate is deleted. The shared query_status /
-- submission_reason / risk_level / query_type / decision / api_body_type enums are reused.

CREATE TYPE request_group_status AS ENUM (
    'DRAFT', 'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTING', 'EXECUTED',
    'REJECTED', 'TIMED_OUT', 'PARTIALLY_EXECUTED', 'FAILED', 'CANCELLED'
);
CREATE TYPE request_group_target_kind AS ENUM ('QUERY', 'API_CALL');
CREATE TYPE request_group_item_status AS ENUM (
    'PENDING', 'EXECUTED', 'FAILED', 'SKIPPED', 'CANCELLED'
);

-- A grouped request (per org). Aggregate ai_risk_level/score = max over members. required_approvals
-- is the resolved per-stage threshold (union across member plans). continue_on_error switches the
-- executor between stop-on-first-failure and run-all-report-each.
CREATE TABLE request_groups (
    id                       UUID                  PRIMARY KEY,
    organization_id          UUID                  NOT NULL,
    name                     VARCHAR(255)          NOT NULL,
    description              TEXT,
    status                   request_group_status  NOT NULL DEFAULT 'DRAFT',
    submission_reason        submission_reason     NOT NULL DEFAULT 'USER_SUBMITTED',
    continue_on_error        BOOLEAN               NOT NULL DEFAULT FALSE,
    scheduled_for            TIMESTAMPTZ,
    submitted_by             UUID                  NOT NULL,
    ai_risk_level            risk_level,
    ai_risk_score            INTEGER,
    required_approvals       INTEGER               NOT NULL DEFAULT 1,
    current_review_stage     INTEGER               NOT NULL DEFAULT 1,
    submitted_ip             VARCHAR(64),
    submitted_user_agent     TEXT,
    execution_started_at     TIMESTAMPTZ,
    execution_completed_at   TIMESTAMPTZ,
    error_message            TEXT,
    version                  BIGINT                NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ           NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_request_groups_org_status ON request_groups (organization_id, status, created_at DESC);
CREATE INDEX idx_request_groups_scheduled
    ON request_groups (scheduled_for) WHERE status = 'APPROVED' AND scheduled_for IS NOT NULL;
CREATE INDEX idx_request_groups_pending_review
    ON request_groups (created_at) WHERE status = 'PENDING_REVIEW';

-- Ordered members. QUERY columns (datasource_id, sql_text, query_type, transactional) XOR API_CALL
-- columns (api_connector_id, verb, request_path, …) per target_kind. ai_analysis_id links the per-
-- member ai_analyses row; status tracks the per-member execution outcome.
CREATE TABLE request_group_items (
    id                     UUID                       PRIMARY KEY,
    group_id               UUID                       NOT NULL REFERENCES request_groups(id) ON DELETE CASCADE,
    sequence_order         INTEGER                    NOT NULL,
    target_kind            request_group_target_kind  NOT NULL,
    -- QUERY members
    datasource_id          UUID,
    sql_text               TEXT,
    query_type             query_type,
    transactional          BOOLEAN                    NOT NULL DEFAULT FALSE,
    -- API_CALL members
    api_connector_id       UUID,
    operation_id           VARCHAR(255),
    verb                   VARCHAR(16),
    request_path           TEXT,
    request_headers        JSONB                      NOT NULL DEFAULT '{}',
    query_params           JSONB                      NOT NULL DEFAULT '{}',
    body_type              api_body_type,
    request_content_type   VARCHAR(255),
    request_body           TEXT,
    form_fields            JSONB                      NOT NULL DEFAULT '[]',
    binary_filename        VARCHAR(255),
    -- AI risk (per member)
    ai_analysis_id         UUID,
    ai_risk_level          risk_level,
    ai_risk_score          INTEGER,
    -- Execution outcome (per member)
    status                 request_group_item_status  NOT NULL DEFAULT 'PENDING',
    result_snapshot        TEXT,
    response_status_code   INTEGER,
    rows_affected          BIGINT,
    error_message          TEXT,
    duration_ms            INTEGER,
    executed_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ                NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_request_group_items_order UNIQUE (group_id, sequence_order)
);

CREATE INDEX idx_request_group_items_group ON request_group_items (group_id, sequence_order);

-- One decision per reviewer/stage covering the WHOLE group (mirror of review_decisions /
-- api_review_decisions). Idempotent on (request_group_id, reviewer_id, stage).
CREATE TABLE group_review_decisions (
    id                 UUID         PRIMARY KEY,
    request_group_id   UUID         NOT NULL REFERENCES request_groups(id) ON DELETE CASCADE,
    reviewer_id        UUID         NOT NULL,
    decision           decision     NOT NULL,
    stage              INTEGER      NOT NULL DEFAULT 1,
    comment            TEXT,
    decided_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_group_review_decisions_request_reviewer_stage
        UNIQUE (request_group_id, reviewer_id, stage)
);

CREATE INDEX idx_group_review_decisions_request ON group_review_decisions (request_group_id);

-- Extend ai_analyses to key off a query request, an API request, or a request-group item (exactly
-- one), keeping the per-org token-budget accounting in DefaultAiRateLimiter unified.
ALTER TABLE ai_analyses ADD COLUMN request_group_item_id UUID
    REFERENCES request_group_items(id) ON DELETE CASCADE;
ALTER TABLE ai_analyses DROP CONSTRAINT chk_ai_analyses_target;
ALTER TABLE ai_analyses ADD CONSTRAINT chk_ai_analyses_target
    CHECK (num_nonnulls(query_request_id, api_request_id, request_group_item_id) = 1);
