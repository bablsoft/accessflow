-- #968: per-submitter usage evidence for the privileged-access report aggregates
-- query_requests by submitted_by; the self-scoped dashboard (AF-498) filters on it too.
-- docs/03-data-model.md has listed idx_query_requests_submitter since the start, but no
-- migration ever created it — this one does, with created_at DESC so MAX(created_at) and the
-- day-bucketed trend scans are served from the index.
CREATE INDEX idx_query_requests_submitter ON query_requests (submitted_by, created_at DESC);
