-- #625: least-privilege intelligence — unused-grant analytics over the audit log.
--
-- grant_usage_summary is a materialised read model with ONE ROW PER LIVE STANDING GRANT, not one row
-- per observed usage. That inversion is deliberate: a never-used grant is the most important row in
-- this feature and it produces no audit events at all, so the table has to be driven off the grant
-- inventory and have usage folded into it. It also makes the over-provisioned report a plain
-- paginated, filterable, sortable query instead of an in-memory join.
--
-- The rows are a derived cache, not a record: GrantUsageAggregationJob reconciles them against the
-- live grants each tick and deletes any whose grant is gone. organization_id / resource_id /
-- permission_id / user_id are bare UUIDs (no FK) like attestation_item, so reconciliation owns the
-- lifecycle rather than a cascade.

CREATE TYPE grant_resource_kind AS ENUM ('DATASOURCE', 'API_CONNECTOR');
CREATE TYPE grant_usage_recommendation AS ENUM (
    'INSUFFICIENT_DATA', 'NEVER_USED', 'STALE', 'OVER_SCOPED', 'ACTIVE');

CREATE TABLE grant_usage_summary (
    id                    UUID                       PRIMARY KEY,
    organization_id       UUID                       NOT NULL,
    resource_kind         grant_resource_kind        NOT NULL,
    resource_id           UUID                       NOT NULL,
    resource_name         TEXT                       NOT NULL,
    -- The live datasource_user_permissions / api_connector_user_permissions row this summarises.
    permission_id         UUID                       NOT NULL,
    user_id               UUID                       NOT NULL,
    user_email            TEXT                       NOT NULL,
    user_display_name     TEXT,
    granted_at            TIMESTAMPTZ                NOT NULL,
    expires_at            TIMESTAMPTZ,
    -- NULL means the grant is UNRESTRICTED (empty allowed_tables / allowed_operations = all).
    -- Distinguishing NULL from 0 is load-bearing: only a scope-limited grant can be OVER_SCOPED.
    granted_target_count  INT,
    -- Distinct tables / operations actually exercised, capped at accessflow.access.usage
    -- .max-tracked-targets. A cap hit is logged, never silently truncated.
    used_targets          JSONB                      NOT NULL DEFAULT '[]',
    used_target_count     INT                        NOT NULL DEFAULT 0,
    usage_count           BIGINT                     NOT NULL DEFAULT 0,
    first_used_at         TIMESTAMPTZ,
    last_used_at          TIMESTAMPTZ,
    -- Start of this row's observation window; the denominator for usage frequency, so a grant
    -- younger than the backfill window is not reported as low-frequency.
    observed_since        TIMESTAMPTZ                NOT NULL,
    recommendation        grant_usage_recommendation NOT NULL DEFAULT 'INSUFFICIENT_DATA',
    -- Last time a staleness nudge was sent for this grant; gates the nudge cooldown.
    nudged_at             TIMESTAMPTZ,
    version               BIGINT                     NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ                NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ                NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_grant_usage_summary_grant
        UNIQUE (organization_id, resource_kind, resource_id, user_id)
);

-- Over-provisioned report: org-scoped, recommendation-filtered, worst (longest idle) first.
-- NULLS FIRST matches the default ordering — a grant that has never been used sorts above one that
-- merely has not been used lately.
CREATE INDEX idx_grant_usage_summary_report
    ON grant_usage_summary (organization_id, recommendation, last_used_at NULLS FIRST);

-- Per-resource lookup: the attestation snapshot resolves one (resource, user) grant at a time.
CREATE INDEX idx_grant_usage_summary_resource
    ON grant_usage_summary (organization_id, resource_kind, resource_id);

-- The audit fold cursor is per ORGANIZATION, not per summary row: the aggregation reads audit_log
-- with one org-scoped range query per tick (audit_log has no index on action, and since V38 the
-- table is owned by the audit role so Flyway cannot add one), which advances every row in the org
-- together. A row created after the cursor moved forward is backfilled explicitly at reconcile
-- time; keeping the cursor out of the summary row is what makes that gap visible rather than silent.
-- The cursor is a KEYSET, not a timestamp: audit_log.created_at is not unique, so resuming from a
-- bare instant either replays the events sharing it or skips them. aggregated_through_id is the
-- audit_log row the fold stopped after; the nil UUID means "start of that instant".
CREATE TABLE grant_usage_watermark (
    organization_id       UUID        PRIMARY KEY,
    aggregated_through    TIMESTAMPTZ NOT NULL,
    aggregated_through_id UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
