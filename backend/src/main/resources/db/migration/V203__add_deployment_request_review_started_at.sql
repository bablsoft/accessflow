-- #984: the instant a deployment request entered PENDING_REVIEW — the approval-latency and
-- timeout-rate axis of the deployment metrics. Stamped by DeploymentRequestStateService.
-- Deliberately not backfilled: the instant is unknown for older rows, and a guessed value would
-- corrupt the latency percentiles.
ALTER TABLE deployment_requests ADD COLUMN review_started_at TIMESTAMPTZ;

COMMENT ON COLUMN deployment_requests.review_started_at IS
    'When the request entered PENDING_REVIEW (#984); NULL for rows that never did or pre-date the column';

-- #984: lead time starts at metadata->>'commit_timestamp', a free-form value any CI trigger
-- writes. Only an ISO-8601 instant with an explicit offset (or Z) is accepted: PostgreSQL's own
-- timestamptz parser also takes 'infinity', 'now' and offset-less strings (read in the session
-- zone), any of which would corrupt the percentiles. Out-of-range fields (Feb 30) return NULL
-- instead of failing the whole metrics query. pg_input_is_valid would do the second half, but it
-- needs PostgreSQL 16 and the supported floor is 15.
CREATE FUNCTION af_try_parse_iso_instant(value TEXT) RETURNS TIMESTAMPTZ
    LANGUAGE plpgsql STABLE AS $$
BEGIN
    IF value IS NULL OR value !~
            '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}(:?\d{2})?)$' THEN
        RETURN NULL;
    END IF;
    RETURN value::TIMESTAMPTZ;
EXCEPTION
    WHEN invalid_datetime_format OR datetime_field_overflow OR invalid_time_zone_displacement_value THEN
        RETURN NULL;
END;
$$;
