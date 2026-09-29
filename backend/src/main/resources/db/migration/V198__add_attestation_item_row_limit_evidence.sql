-- #1084: record the row limit enforcement actually applies next to the reviewed grant's own value.
-- effective_row_limit / row_limit_source are nullable and never backfilled: attestation_item is
-- frozen evidence of campaign open, so items snapshotted before this change carry no applied-limit
-- evidence rather than today's value.
ALTER TABLE attestation_item
    ADD COLUMN row_limit_override  INTEGER,
    ADD COLUMN effective_row_limit INTEGER,
    ADD COLUMN row_limit_source    TEXT;

-- The grant's own override was already frozen at open inside permission_snapshot, so lifting it
-- into the column is still campaign-open evidence, not today's value.
UPDATE attestation_item
SET row_limit_override = (permission_snapshot ->> 'row_limit_override')::INTEGER
WHERE permission_snapshot ->> 'row_limit_override' IS NOT NULL;
