-- #971 (epic #970): connector rate limits, quotas and budgets — persistence foundation only.
-- Nothing reads these columns yet; the limiter (#972) and accrual (#973) follow.
--
-- Every limit is nullable and NULL means unlimited, so every existing connector reads back
-- unlimited. Cost is an integer number of micros of one unlabelled accounting unit (no currency).

ALTER TABLE api_connectors
    ADD COLUMN calls_per_minute               INTEGER,
    ADD COLUMN calls_per_day                  INTEGER,
    ADD COLUMN per_user_share_percent         INTEGER,
    ADD COLUMN cost_per_call_micros           BIGINT,
    ADD COLUMN monthly_budget_micros          BIGINT,
    -- First day of the UTC month in which the budget-exhausted notification was sent: the
    -- one-shot-per-month guard (the deployment_requests.release_notified_at precedent).
    ADD COLUMN budget_exceeded_notified_month DATE,
    ADD CONSTRAINT chk_api_connectors_calls_per_minute
        CHECK (calls_per_minute IS NULL OR calls_per_minute > 0),
    ADD CONSTRAINT chk_api_connectors_calls_per_day
        CHECK (calls_per_day IS NULL OR calls_per_day > 0),
    ADD CONSTRAINT chk_api_connectors_per_user_share_percent
        CHECK (per_user_share_percent IS NULL OR per_user_share_percent BETWEEN 1 AND 100),
    ADD CONSTRAINT chk_api_connectors_cost_per_call_micros
        CHECK (cost_per_call_micros IS NULL OR cost_per_call_micros >= 0),
    ADD CONSTRAINT chk_api_connectors_monthly_budget_micros
        CHECK (monthly_budget_micros IS NULL OR monthly_budget_micros > 0);

-- A throttled request stays APPROVED (no THROTTLED status; query_status is shared and untouched).
ALTER TABLE api_requests
    ADD COLUMN throttled_until TIMESTAMPTZ,
    ADD COLUMN throttle_count  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cost_micros     BIGINT;

-- Backs the throttled-retry scan (#972); same shape as idx_api_requests_scheduled (V101).
CREATE INDEX idx_api_requests_throttled
    ON api_requests (throttled_until) WHERE status = 'APPROVED' AND throttled_until IS NOT NULL;

-- Daily per-user rollup keyed (connector, user, UTC date). The day is computed in Java from the
-- injected Clock, never with date_trunc. organization_id / user_id are bare UUIDs, the
-- grant_usage_summary (V135) convention for a derived rollup. No version column: rows are written
-- only by one atomic INSERT ... ON CONFLICT ... DO UPDATE that adds deltas.
CREATE TABLE api_connector_usage_daily (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL,
    connector_id    UUID        NOT NULL REFERENCES api_connectors (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL,
    usage_date      DATE        NOT NULL,
    calls           BIGINT      NOT NULL DEFAULT 0,
    failed_calls    BIGINT      NOT NULL DEFAULT 0,
    throttled_calls BIGINT      NOT NULL DEFAULT 0,
    spend_micros    BIGINT      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_api_connector_usage_daily_key UNIQUE (connector_id, user_id, usage_date)
);

CREATE INDEX idx_api_connector_usage_daily_org_connector_date
    ON api_connector_usage_daily (organization_id, connector_id, usage_date);
