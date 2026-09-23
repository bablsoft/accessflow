-- #923: execution history of @Scheduled jobs, written by the scheduling module's recorder.
--
-- Platform-scoped, not org-scoped: jobs run per process and several sweep every organization in one
-- pass, so there is deliberately no organization_id. No FKs — the table references nothing and is
-- pruned on its own clock by JobExecutionRetentionJob (age + per-job cap).
--
-- A row opens as RUNNING once the job's ShedLock lock is acquired and is closed as SUCCESS/FAILED
-- when the method returns. Lock-skipped ticks are never written. A RUNNING row older than the job's
-- lockAtMostFor is reported as abandoned (the replica died mid-run); that is derived, never stored.

CREATE TYPE job_execution_status AS ENUM ('RUNNING', 'SUCCESS', 'FAILED');

CREATE TABLE job_executions (
    id            UUID                 PRIMARY KEY,
    job_name      TEXT                 NOT NULL,
    lock_name     TEXT,
    instance_id   TEXT,
    started_at    TIMESTAMPTZ          NOT NULL,
    finished_at   TIMESTAMPTZ,
    duration_ms   BIGINT,
    status        job_execution_status NOT NULL,
    error_class   TEXT,
    -- Truncated on write (2000 chars) so a stack-traced message never becomes an unbounded column.
    error_message TEXT
);

CREATE INDEX idx_job_executions_job_started ON job_executions (job_name, started_at DESC);
CREATE INDEX idx_job_executions_started ON job_executions (started_at);
CREATE INDEX idx_job_executions_failed ON job_executions (job_name, started_at DESC) WHERE status = 'FAILED';
