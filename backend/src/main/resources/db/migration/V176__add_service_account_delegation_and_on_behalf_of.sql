-- #874 (epic #867): on-behalf-of attribution for API-key callers. Two intents:
--
-- 1. service_account_delegated_principals — which humans a service account may act FOR. A row is a
--    standing grant (human → agent) that the X-AccessFlow-On-Behalf-Of header is checked against;
--    it confers NO authority: the effective permission set stays the service account's own. It is
--    the mirror image of review_delegations (#622), which borrows a human's review AUTHORITY, and
--    the two must never be confused — see V143 for why on_behalf_of_user_id on the decision tables
--    means something else entirely.
--    Revocation is soft (revoked_at) so the grant that authorised a past submission survives as
--    evidence; the partial unique index keeps exactly one LIVE row per (agent, human) pair.
--
-- 2. on_behalf_of_user_id on the four submit→review request tables (and the rollback review that
--    copies its request's submitted_by). The self-approval invariant widens from "reviewer != the
--    submitter" to "reviewer is neither the submitter nor the human the submitter acted for" —
--    without it, "Alice tells her agent to submit, then Alice approves" passes the existing guard
--    because the submitter is the agent. No FKs, matching V143: attribution must survive deletion
--    of either party. Nullable: every existing row and every human-submitted request stays NULL.
--
-- audit_log gets NO column (AuditChainHasher canonicalises a fixed ten-field list; adding one would
-- change the canonical form of every historical row). Attribution rides in metadata, which is
-- already inside the MAC.

CREATE TABLE service_account_delegated_principals (
    id                      UUID        PRIMARY KEY,
    organization_id         UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_account_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    principal_user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    granted_by              UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at              TIMESTAMPTZ,
    revoked_at              TIMESTAMPTZ,
    revoked_by              UUID,
    CONSTRAINT chk_sa_delegated_principals_not_self
        CHECK (service_account_user_id <> principal_user_id)
);

CREATE UNIQUE INDEX uq_sa_delegated_principals_live
    ON service_account_delegated_principals (service_account_user_id, principal_user_id)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_sa_delegated_principals_principal
    ON service_account_delegated_principals (organization_id, principal_user_id);

ALTER TABLE query_requests              ADD COLUMN on_behalf_of_user_id UUID;
ALTER TABLE api_requests                ADD COLUMN on_behalf_of_user_id UUID;
ALTER TABLE deployment_requests         ADD COLUMN on_behalf_of_user_id UUID;
ALTER TABLE deployment_rollback_reviews ADD COLUMN on_behalf_of_user_id UUID;
ALTER TABLE request_groups              ADD COLUMN on_behalf_of_user_id UUID;

CREATE INDEX idx_query_requests_on_behalf_of
    ON query_requests (on_behalf_of_user_id) WHERE on_behalf_of_user_id IS NOT NULL;
CREATE INDEX idx_api_requests_on_behalf_of
    ON api_requests (on_behalf_of_user_id) WHERE on_behalf_of_user_id IS NOT NULL;
CREATE INDEX idx_deployment_requests_on_behalf_of
    ON deployment_requests (on_behalf_of_user_id) WHERE on_behalf_of_user_id IS NOT NULL;
CREATE INDEX idx_request_groups_on_behalf_of
    ON request_groups (on_behalf_of_user_id) WHERE on_behalf_of_user_id IS NOT NULL;

-- The reverse direction is barred from now on (DefaultReviewDelegationService refuses a non-HUMAN
-- delegate), but a reviewer delegation created before this release could already name a service
-- account — bootstrap accounts have been creatable since #868. Retire any such live grant now:
-- review authority is never handed to an agent. revoked_by stays NULL (no actor), which is what
-- the API renders as a system revocation.
UPDATE review_delegations d
   SET revoked_at = now()
 WHERE d.revoked_at IS NULL
   AND EXISTS (SELECT 1 FROM users u
                WHERE u.id = d.delegate_id AND u.principal_type = 'SERVICE_ACCOUNT');
