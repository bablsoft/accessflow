-- AF-384: access recertification / periodic attestation campaigns. A campaign snapshots the current
-- standing datasource permissions into per-grant items at open time; reviewers certify/revoke each
-- item; at the due date a scheduled job closes the campaign and applies the per-campaign default
-- (KEEP / REVOKE) to anything still PENDING. organization_id / datasource_id / created_by / the
-- subject + permission columns are bare UUIDs (no FK), like break_glass_events / query_snapshots /
-- audit_log, so the campaign and its items survive even after the permission row, datasource, or user
-- is deleted — the items are a frozen evidence record. The single real FK is item → campaign.

CREATE TYPE attestation_campaign_status AS ENUM ('SCHEDULED', 'OPEN', 'CLOSED', 'CANCELLED');
CREATE TYPE attestation_campaign_scope AS ENUM ('ORGANIZATION', 'DATASOURCE');
CREATE TYPE attestation_item_decision AS ENUM ('PENDING', 'CERTIFIED', 'REVOKED');
CREATE TYPE attestation_item_close_reason AS ENUM ('REVIEWER', 'AUTO_DEFAULT_KEEP', 'AUTO_DEFAULT_REVOKE');
CREATE TYPE attestation_pending_default AS ENUM ('KEEP', 'REVOKE');

CREATE TABLE attestation_campaign (
    id                UUID                        PRIMARY KEY,
    organization_id   UUID                        NOT NULL,
    name              TEXT                        NOT NULL,
    description       TEXT,
    scope             attestation_campaign_scope  NOT NULL,
    -- Non-null only for DATASOURCE-scoped campaigns; app-enforced (a partial CHECK would still allow
    -- a stray datasource_id on an ORGANIZATION row, so the invariant lives in the service layer).
    datasource_id     UUID,
    status            attestation_campaign_status NOT NULL DEFAULT 'SCHEDULED',
    pending_default   attestation_pending_default NOT NULL DEFAULT 'KEEP',
    scheduled_open_at TIMESTAMPTZ                 NOT NULL,
    due_at            TIMESTAMPTZ                 NOT NULL,
    opened_at         TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    total_items       INT                         NOT NULL DEFAULT 0,
    created_by        UUID                        NOT NULL,
    version           BIGINT                      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ                 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ                 NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Admin campaign list: org-scoped, status-filtered, newest first.
CREATE INDEX idx_attestation_campaign_org_status
    ON attestation_campaign (organization_id, status, created_at DESC);

-- Open-job scan: campaigns whose scheduled_open_at has passed and are still SCHEDULED.
CREATE INDEX idx_attestation_campaign_scheduled_open
    ON attestation_campaign (scheduled_open_at) WHERE status = 'SCHEDULED';

-- Close-job scan: OPEN campaigns whose due_at has passed.
CREATE INDEX idx_attestation_campaign_due
    ON attestation_campaign (due_at) WHERE status = 'OPEN';

CREATE TABLE attestation_item (
    id                        UUID                          PRIMARY KEY,
    campaign_id               UUID                          NOT NULL
        REFERENCES attestation_campaign(id) ON DELETE CASCADE,
    organization_id           UUID                          NOT NULL,
    -- Bare reference to the materialised datasource_user_permissions row this item attests; used as
    -- the revoke target. Tolerated as already-gone (the grant may be revoked out-of-band first).
    permission_id             UUID                          NOT NULL,
    datasource_id             UUID                          NOT NULL,
    datasource_name           TEXT                          NOT NULL,
    subject_user_id           UUID                          NOT NULL,
    subject_user_email        TEXT                          NOT NULL,
    subject_user_display_name TEXT,
    can_read                  BOOLEAN                       NOT NULL DEFAULT FALSE,
    can_write                 BOOLEAN                       NOT NULL DEFAULT FALSE,
    can_ddl                   BOOLEAN                       NOT NULL DEFAULT FALSE,
    can_break_glass           BOOLEAN                       NOT NULL DEFAULT FALSE,
    permission_expires_at     TIMESTAMPTZ,
    permission_created_at     TIMESTAMPTZ,
    -- Full serialized permission view (incl. allowed schemas/tables, restricted columns, row-limit
    -- override) so the exact grant shape is preserved verbatim for forensics after the source changes.
    permission_snapshot       JSONB                         NOT NULL,
    decision                  attestation_item_decision     NOT NULL DEFAULT 'PENDING',
    close_reason              attestation_item_close_reason,
    decided_by                UUID,
    decided_at                TIMESTAMPTZ,
    decision_comment          TEXT,
    version                   BIGINT                        NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ                   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- One item per (campaign, permission): backstops a double-insert if the open transaction re-runs.
    CONSTRAINT uq_attestation_item_campaign_permission UNIQUE (campaign_id, permission_id)
);

-- Reviewer worklist + close sweep: items of a campaign filtered by decision.
CREATE INDEX idx_attestation_item_campaign_decision
    ON attestation_item (campaign_id, decision);

-- "What did user X have attested across campaigns" evidence lookups.
CREATE INDEX idx_attestation_item_org_subject
    ON attestation_item (organization_id, subject_user_id);
