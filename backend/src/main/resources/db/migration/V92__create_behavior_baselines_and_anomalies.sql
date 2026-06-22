-- AF-383: User Behavior Analytics (UBA). Rolling per-(user,datasource) behavioural baselines are
-- built ONLY from audit_log metadata (never query result data — a privacy invariant). A scheduled
-- job aggregates each user's recent activity into feature observations, compares them against the
-- baseline statistically (z-score / IQR / categorical novelty / off-hours), and persists deviations
-- as behavior_anomaly rows that escalate the user's next query (routing), fan out to admins
-- (notifications + WebSocket), and surface on an admin dashboard.

CREATE TYPE behavior_anomaly_status AS ENUM ('OPEN', 'ACKNOWLEDGED', 'DISMISSED');

-- One row per (org, user, datasource). features holds the rolling profile as a JSONB blob: per
-- scalar feature (query_count / distinct_tables / rows_returned / error_rate) a bounded list of
-- recent per-window observations (mean/stddev/p25/p75 are computed from it on demand), a 24-bucket
-- cumulative active-hour histogram, and cumulative query-type / table frequency maps for novelty
-- detection. A blob (not one row per feature) is chosen because the detector reads every feature as
-- a unit, the shapes are heterogeneous, and adding a feature later needs no migration.
CREATE TABLE behavior_baseline (
    id                UUID        PRIMARY KEY,
    organization_id   UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id           UUID        NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    datasource_id     UUID        NOT NULL REFERENCES datasources(id)   ON DELETE CASCADE,
    features          JSONB       NOT NULL DEFAULT '{}',
    sample_size       INTEGER     NOT NULL DEFAULT 0,   -- number of windows folded into this baseline
    last_window_start TIMESTAMPTZ,                      -- window_start of the most recent fold (idempotency)
    last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_behavior_baseline_user_ds
    ON behavior_baseline (organization_id, user_id, datasource_id);

CREATE TABLE behavior_anomaly (
    id              UUID                    PRIMARY KEY,
    organization_id UUID                    NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID                    NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    datasource_id   UUID                    NOT NULL REFERENCES datasources(id)   ON DELETE CASCADE,
    feature         TEXT                    NOT NULL,   -- query_count | active_hours | distinct_tables | ...
    score           DOUBLE PRECISION        NOT NULL,   -- z-score / IQR multiple magnitude (novelty = configured floor)
    observed_value  DOUBLE PRECISION,                   -- numeric observed (null for categorical novelty)
    baseline_mean   DOUBLE PRECISION,
    baseline_stddev DOUBLE PRECISION,
    detail          JSONB                   NOT NULL DEFAULT '{}', -- observed vs baseline context (hour, table, type, method)
    ai_summary      TEXT,                               -- nullable; freeform AI explanation (fail-safe)
    status          behavior_anomaly_status NOT NULL DEFAULT 'OPEN',
    detected_at     TIMESTAMPTZ             NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_by UUID                    REFERENCES users(id),
    acknowledged_at TIMESTAMPTZ,
    window_start    TIMESTAMPTZ             NOT NULL,
    window_end      TIMESTAMPTZ             NOT NULL,
    version         BIGINT                  NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ             NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ             NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Admin list: org-scoped, status-filtered, newest first.
CREATE INDEX idx_behavior_anomaly_org_status
    ON behavior_anomaly (organization_id, status, detected_at DESC);
-- Routing lookup (hasActiveAnomaly) + per-user / query-detail badge.
CREATE INDEX idx_behavior_anomaly_user_ds_status
    ON behavior_anomaly (organization_id, user_id, datasource_id, status);
-- Idempotency: the every-15-min job must not re-create (or re-page) the same window's anomaly.
CREATE UNIQUE INDEX uq_behavior_anomaly_dedup
    ON behavior_anomaly (organization_id, user_id, datasource_id, feature, window_start);
