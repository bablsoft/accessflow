CREATE TABLE review_plans (
    id                       UUID         PRIMARY KEY,
    organization_id          UUID         NOT NULL REFERENCES organizations(id),
    name                     VARCHAR(255) NOT NULL,
    description              TEXT,
    requires_ai_review       BOOLEAN      NOT NULL DEFAULT true,
    requires_human_approval  BOOLEAN      NOT NULL DEFAULT true,
    min_approvals_required   INTEGER      NOT NULL DEFAULT 1,
    approval_timeout_hours   INTEGER      NOT NULL DEFAULT 24,
    auto_approve_reads       BOOLEAN      NOT NULL DEFAULT false,
    notify_channels          TEXT[],
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_plan_approvers (
    id             UUID          PRIMARY KEY,
    review_plan_id UUID          NOT NULL REFERENCES review_plans(id),
    user_id        UUID          REFERENCES users(id),
    role           user_role_type,         -- ADMIN | REVIEWER: any user with this role can approve
    stage          INTEGER       NOT NULL  -- multi-stage sequential approval ordering
);

-- Wire the FK stub added in V3 now that review_plans exists
ALTER TABLE datasources
    ADD CONSTRAINT fk_datasources_review_plan
    FOREIGN KEY (review_plan_id) REFERENCES review_plans(id);
