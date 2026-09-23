-- #881 (epic #870): optimistic locking on drift findings.
--
-- A drift scan reconciles findings row by row over the length of a scan, while an admin can
-- acknowledge one at any moment. Without a version column the scan's stale copy of an OPEN finding
-- would be saved back over the acknowledgement, silently undoing it while its audit row stayed. With
-- one, whichever write loses is refused: the scan skips the row until its next run, and the
-- acknowledge endpoint answers 409 and asks the caller to retry.

ALTER TABLE schema_drift_findings ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
