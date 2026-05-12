-- AF-66: tamper-evident HMAC-SHA256 hash chain for audit_log.
-- previous_hash / current_hash are nullable so the columns can be added zero-downtime; rows
-- written before V26 keep NULL hashes and are treated as "pre-chain" by the verifier
-- (DefaultAuditLogService.verify). New rows inserted by the chain-aware service always populate
-- current_hash; previous_hash is NULL only for the very first row in each organization's chain.
ALTER TABLE audit_log
    ADD COLUMN previous_hash BYTEA,
    ADD COLUMN current_hash  BYTEA;

-- Supports both the per-org "find latest row to chain from" lookup on insert and the ASC walk
-- the verifier performs over (organization_id, created_at, id).
CREATE INDEX idx_audit_log_org_created_id
    ON audit_log (organization_id, created_at DESC, id DESC);
