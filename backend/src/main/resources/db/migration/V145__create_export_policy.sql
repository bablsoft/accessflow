-- #626: result-export governance / DLP. Per-datasource policies governing how a query's
-- persisted result set may leave AccessFlow — via GET /queries/{id}/results/export (CSV/PDF)
-- and via the results-CSV attachment on recurring-execution emails. Resolution runs at
-- EXPORT time, not execution time; classification presence is computed by matching the
-- persisted result's columns and the query's referenced tables against data_classification_tag.
--
-- applies_to_* polarity follows row_security_policy (the INVERSE of masking_policy.reveal_to_*):
-- all three applies_to_* empty ⇒ the policy applies to EVERY exporter (governance-safe
-- default); a non-empty list narrows it by role name / group id / user id. There is NO
-- implicit ADMIN bypass. When several policies apply, the most restrictive mode wins
-- (ALLOW < WATERMARK < ROW_CAP < DENY_CLASSIFIED); the effective row cap is the minimum
-- across applicable ROW_CAP rows; a DENY_CLASSIFIED policy only participates when the result
-- actually contains a column classified with one of its deny_classifications (empty = any).
-- No applicable policy ⇒ ALLOW, unwatermarked. WATERMARK and ROW_CAP exports are both
-- watermarked — a capped file must carry its cap provenance.
--
-- deny_classifications stores data_classification enum NAMES as TEXT[] (the applies_to_roles
-- precedent) rather than data_classification[] — Hibernate's array binding for PG enum element
-- types is unreliable, and the service validates values against the Java enum.

CREATE TYPE export_policy_mode AS ENUM ('ALLOW', 'WATERMARK', 'ROW_CAP', 'DENY_CLASSIFIED');

CREATE TABLE export_policy (
    id                   UUID               PRIMARY KEY,
    organization_id      UUID               NOT NULL REFERENCES organizations(id),
    datasource_id        UUID               NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    mode                 export_policy_mode NOT NULL,
    row_cap              INTEGER,
    deny_classifications TEXT[],
    applies_to_roles     TEXT[],
    applies_to_group_ids UUID[],
    applies_to_user_ids  UUID[],
    enabled              BOOLEAN            NOT NULL DEFAULT true,
    version              BIGINT             NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_export_policy_ds_enabled
    ON export_policy (organization_id, datasource_id, enabled);
