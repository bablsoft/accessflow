-- #622: out-of-office reviewer delegation. During [starts_at, ends_at) the delegate is an eligible
-- approver everywhere the delegator was — query review (workflow), governed API-request review
-- (apigov), and grouped requests (requestgroups).
--
-- The table lives in `core`, not `workflow`, for two reasons. First, all three review modules
-- already depend on core.api, whereas owning it in workflow would force apigov -> workflow, the
-- exact edge ApiBreakGlassReviewListener's javadoc refuses because it closes an
-- access -> apigov -> workflow -> access cycle. Second, QueryRequestRepository.findPendingForReviewer
-- must filter on these rows and lives in core; a workflow-owned entity would still resolve in the
-- JPQL (one persistence unit) and Spring Modulith would NOT catch it, because JPQL is a string and
-- not an import. That is exactly the kind of invisible coupling to keep out.
--
-- Delegation never grants a permission: the acting user's own Permission set is checked first and
-- independently, so a delegation from someone with review rights confers nothing on a delegate
-- without them.
--
-- The delegator's role name is deliberately NOT stored — it is resolved by joining users at read
-- time via UserEntity.roleName(), so a role change or role removal mid-window takes effect
-- immediately. A delegation is a pointer to an identity, never a frozen copy of its powers.

CREATE TYPE review_delegation_scope_kind AS ENUM ('DATASOURCE', 'API_CONNECTOR');

CREATE TABLE review_delegations (
    id                UUID                          PRIMARY KEY,
    organization_id   UUID                          NOT NULL REFERENCES organizations(id),
    delegator_id      UUID                          NOT NULL REFERENCES users(id),
    delegate_id       UUID                          NOT NULL REFERENCES users(id),
    -- Both NULL = unrestricted, i.e. every review queue. scope_id is intentionally not an FK: it is
    -- polymorphic over datasources(id) and api_connectors(id) depending on scope_kind, so no single
    -- FK can express it — the same bare-UUID convention V106 uses for cross-module references. A
    -- dangling scope_id after the resource is deleted fails closed (nothing matches), so it is
    -- harmless. The service layer validates the reference on write.
    scope_kind        review_delegation_scope_kind,
    scope_id          UUID,
    reason            TEXT,
    starts_at         TIMESTAMPTZ                   NOT NULL,
    ends_at           TIMESTAMPTZ                   NOT NULL,
    -- Revocation is soft, never DELETE: the row is the evidence that decisions already recorded
    -- against this delegation were validly authorised at the time.
    revoked_at        TIMESTAMPTZ,
    revoked_by        UUID                          REFERENCES users(id),
    created_by        UUID                          NOT NULL REFERENCES users(id),
    version           BIGINT                        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_delegations_scope
        CHECK (num_nonnulls(scope_kind, scope_id) IN (0, 2)),
    CONSTRAINT chk_review_delegations_window
        CHECK (ends_at > starts_at),
    -- Delegating to yourself is a no-op that would only muddy the on-behalf-of audit trail.
    CONSTRAINT chk_review_delegations_not_self
        CHECK (delegator_id <> delegate_id)
);

-- Deliberately NO unique or EXCLUDE constraint on (delegator, delegate, scope) overlap. Overlapping
-- windows must stay legal ("extend my leave by another week"), and since eligibility is a union
-- over identities, an overlap is semantically a no-op.

-- Hot path, run on every review-queue render and every decision: "whose reviewer identity may this
-- acting user borrow right now?". Partial, so revoked rows never enter the scan.
CREATE INDEX idx_review_delegations_active_delegate
    ON review_delegations (organization_id, delegate_id, starts_at, ends_at)
    WHERE revoked_at IS NULL;

-- Reverse direction: "who is covering me", and the delegator's own list on the profile page.
CREATE INDEX idx_review_delegations_active_delegator
    ON review_delegations (organization_id, delegator_id, starts_at, ends_at)
    WHERE revoked_at IS NULL;

-- Org-wide oversight listing (GET /admin/review-delegations), including revoked and expired rows.
CREATE INDEX idx_review_delegations_org
    ON review_delegations (organization_id, created_at DESC);
