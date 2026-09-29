-- #1084: record the row limit enforcement actually applies next to the reviewed grant's own value.
-- Nullable and never backfilled: attestation_item is frozen evidence of campaign open, so items
-- snapshotted before this change carry no row-limit evidence rather than today's value.
ALTER TABLE attestation_item
    ADD COLUMN row_limit_override  INTEGER,
    ADD COLUMN effective_row_limit INTEGER,
    ADD COLUMN row_limit_source    TEXT;
