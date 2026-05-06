-- Drop FKs from audit_log to organizations / users so audit history survives org/user deletion
-- and so the audit module persistence stays modulith-isolated. The columns themselves remain.
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_organization_id_fkey;
ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS audit_log_actor_id_fkey;
