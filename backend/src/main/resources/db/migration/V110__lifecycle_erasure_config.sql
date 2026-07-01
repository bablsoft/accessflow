-- AF-519: Configurable & request-based data erasure. Enriches the AF-499 lifecycle model with a
-- shared configuration shape: arbitrary conditions (a structured predicate list as JSONB plus a raw
-- WHERE escape hatch) and an optional per-policy cron schedule (decoupled from the global scan
-- interval). All columns are additive and nullable/defaulted, so age-window retention targeting keeps
-- working unchanged and the deploy is zero-downtime.

-- Retention policies gain: structured conditions + raw WHERE (compiled into bound predicates at
-- execution), an optional cron schedule, and cron bookkeeping (last/next run timestamps).
ALTER TABLE retention_policies
    ADD COLUMN conditions    JSONB,
    ADD COLUMN raw_where     TEXT,
    ADD COLUMN cron_schedule TEXT,
    ADD COLUMN last_run_at   TIMESTAMPTZ,
    ADD COLUMN next_run_at   TIMESTAMPTZ;

-- Cron due-scan: enabled policies carrying a cron schedule, ordered by their next fire time.
CREATE INDEX idx_retention_policies_cron
    ON retention_policies (next_run_at) WHERE cron_schedule IS NOT NULL AND enabled = TRUE;

-- Deletion requests gain the same rich config so a user-submitted erasure can carry its own target
-- table/columns + conditions instead of only subject_type/subject_identifier (which stay supported as
-- one condition shape for backward compatibility).
ALTER TABLE deletion_requests
    ADD COLUMN target_table   TEXT,
    ADD COLUMN target_columns TEXT[]  NOT NULL DEFAULT '{}',
    ADD COLUMN conditions     JSONB,
    ADD COLUMN raw_where      TEXT;

-- A request may now be driven entirely by target_table + conditions/raw_where, so the subject shape
-- becomes optional (still supported as one condition shape for backward compatibility).
ALTER TABLE deletion_requests
    ALTER COLUMN subject_type       DROP NOT NULL,
    ALTER COLUMN subject_identifier DROP NOT NULL;
