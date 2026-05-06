-- Indexes specified in docs/03-data-model.md but not yet created.
CREATE INDEX idx_audit_log_created  ON audit_log(organization_id, created_at DESC);
CREATE INDEX idx_audit_log_actor    ON audit_log(actor_id, created_at DESC);
CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);
