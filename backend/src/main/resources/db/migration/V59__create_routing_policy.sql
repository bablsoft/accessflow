-- AF-379: policy-as-code routing engine. Admin-configured, versioned, ordered rules evaluated
-- after AI analysis and before reviewer fanout. The first enabled policy (by ascending priority)
-- whose typed condition tree matches decides routing: auto-approve, auto-reject, require N
-- approvals, or escalate. On no match the query falls through to the datasource's review_plan
-- exactly as before. Conditions draw only on signals AccessFlow already computes (query type,
-- referenced tables, AI risk, requester role / group membership, time-of-day, WHERE / LIMIT
-- presence, transactional flag) and are stored as a typed JSONB tree -- no external policy engine.

CREATE TYPE routing_action AS ENUM
    ('AUTO_APPROVE', 'AUTO_REJECT', 'REQUIRE_APPROVALS', 'ESCALATE');

CREATE TABLE routing_policy (
    id                 UUID           PRIMARY KEY,
    organization_id    UUID           NOT NULL REFERENCES organizations(id),
    datasource_id      UUID           REFERENCES datasources(id),   -- NULL = org-wide
    name               VARCHAR(255)   NOT NULL,
    description        VARCHAR(2000),
    priority           INTEGER        NOT NULL,
    enabled            BOOLEAN        NOT NULL DEFAULT true,
    condition          JSONB          NOT NULL DEFAULT '{}',
    action             routing_action NOT NULL,
    -- REQUIRE_APPROVALS: absolute minimum approvals; ESCALATE: extra approvals (delta on top of
    -- the review plan's requirement); NULL for AUTO_APPROVE / AUTO_REJECT.
    required_approvals INTEGER,
    -- Optional admin-authored reason surfaced in the audit row and the query detail timeline.
    reason             VARCHAR(500),
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Backs the first-match-by-priority scan: enabled policies for one org, priority ascending.
CREATE INDEX idx_routing_policy_org_enabled_priority
    ON routing_policy (organization_id, enabled, priority);

-- Priority must be unique per org so the ordering is deterministic.
CREATE UNIQUE INDEX uq_routing_policy_org_priority
    ON routing_policy (organization_id, priority);

CREATE TABLE routing_decision (
    id                      UUID           PRIMARY KEY,
    query_request_id        UUID           NOT NULL REFERENCES query_requests(id),
    -- SET NULL on policy deletion so historical decisions (and their audit trail) survive.
    matched_policy_id       UUID           REFERENCES routing_policy(id) ON DELETE SET NULL,
    action                  routing_action NOT NULL,
    -- Resolved absolute minimum approvals (ESCALATE / REQUIRE_APPROVALS); NULL otherwise.
    effective_min_approvals INTEGER,
    reason                  VARCHAR(500),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One routing decision per query.
CREATE UNIQUE INDEX uq_routing_decision_query ON routing_decision (query_request_id);
