-- #582: opt-in query pre-approval under an active JIT access grant (AF-378).
ALTER TABLE access_grant_request
    ADD COLUMN pre_approve_queries BOOLEAN NOT NULL DEFAULT false;

-- Bare UUID (no FK) — mirrors the granted_permission_id / ai_analysis_id convention: the
-- grant row's lifecycle (expiry, revocation) is independent of the query's audit trail.
ALTER TABLE query_requests
    ADD COLUMN approved_by_grant_id UUID;

-- Backs the per-submission lookup of active pre-approving grants.
CREATE INDEX idx_access_grant_request_pre_approve
    ON access_grant_request (requester_id, datasource_id)
    WHERE status = 'APPROVED' AND pre_approve_queries;
