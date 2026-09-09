-- AF-659: consecutive-miss counter backing the PENDING -> STALE transition added in V164.
--
-- Incremented by DiscoveryScanService's post-scan sweep for every PENDING finding that belongs to a
-- table the run actually sampled but did not re-propose; reset to 0 whenever the finding is
-- re-detected. NOT NULL with a DEFAULT so existing rows start from zero and rolling deploys where
-- old pods still insert without the column keep working.
ALTER TABLE discovery_finding
    ADD COLUMN missed_scan_count INTEGER NOT NULL DEFAULT 0;
