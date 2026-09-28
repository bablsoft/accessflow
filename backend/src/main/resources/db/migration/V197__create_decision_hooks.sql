-- #945: external policy decision hook. An operator-run HTTP endpoint the routing chain consults
-- when no local routing policy matched, just before the grant-covered fast path. It may leave the
-- decision alone, raise the approval count or reject — never approve — and every failure sends the
-- query to human review.

CREATE TYPE routing_decision_source AS ENUM ('POLICY', 'DECISION_HOOK');
CREATE TYPE decision_hook_outcome   AS ENUM ('ALLOW', 'ESCALATE', 'REQUIRE_APPROVALS', 'REJECT',
                                             'FAILED');
CREATE TYPE decision_hook_failure   AS ENUM ('TIMEOUT', 'TRANSPORT_ERROR', 'NON_2XX', 'UNPARSEABLE',
                                             'SIGNATURE_MISMATCH', 'INVALID_DECISION',
                                             'SSRF_BLOCKED', 'CIRCUIT_OPEN');

CREATE TABLE decision_hooks (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- NULL = the organization default; otherwise the hook of that one datasource.
    datasource_id    UUID          REFERENCES datasources(id) ON DELETE CASCADE,
    name             VARCHAR(255)  NOT NULL,
    endpoint_url     VARCHAR(2048) NOT NULL,
    timeout_ms       INTEGER       NOT NULL DEFAULT 2000,
    -- AES-256-GCM ciphertext of the HMAC signing secret; never returned by the API.
    secret_encrypted TEXT          NOT NULL,
    include_sql      BOOLEAN       NOT NULL DEFAULT FALSE,
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_decision_hooks_timeout CHECK (timeout_ms BETWEEN 100 AND 10000)
);

-- At most one hook per datasource and one organization default. Two partial indexes because
-- NULLs never collide in a plain UNIQUE (the sql_review_rulesets precedent, V170).
CREATE UNIQUE INDEX uq_decision_hooks_org_datasource
    ON decision_hooks (organization_id, datasource_id)
    WHERE datasource_id IS NOT NULL;
CREATE UNIQUE INDEX uq_decision_hooks_org_default
    ON decision_hooks (organization_id)
    WHERE datasource_id IS NULL;

-- Who decided a routing decision: a routing policy (every pre-#945 row) or the decision hook.
ALTER TABLE routing_decision
    ADD COLUMN source routing_decision_source NOT NULL DEFAULT 'POLICY';
-- Bare UUID: the hook may be deleted while its decisions stay on record.
ALTER TABLE routing_decision ADD COLUMN decision_hook_id UUID;

-- One row per query the hook was consulted for, whatever it answered — ALLOW and failures
-- included, which routing_decision alone would not record.
CREATE TABLE decision_hook_results (
    id                  UUID                  PRIMARY KEY,
    query_request_id    UUID                  NOT NULL REFERENCES query_requests(id)
                                                  ON DELETE CASCADE,
    decision_hook_id    UUID                  NOT NULL,
    decision_hook_name  VARCHAR(255)          NOT NULL,
    outcome             decision_hook_outcome NOT NULL,
    failure             decision_hook_failure,
    requested_approvals INTEGER,
    reason              VARCHAR(500),
    http_status         INTEGER,
    latency_ms          BIGINT                NOT NULL,
    created_at          TIMESTAMPTZ           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_decision_hook_results_failure
        CHECK ((outcome = 'FAILED') = (failure IS NOT NULL))
);

CREATE UNIQUE INDEX uq_decision_hook_results_query ON decision_hook_results (query_request_id);
