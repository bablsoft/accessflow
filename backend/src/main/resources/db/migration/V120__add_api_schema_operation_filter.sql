-- AF-614: import-time operation filter per uploaded schema.
-- parsed_operations keeps the complete parsed catalog; operation_filter (nullable) narrows the
-- governed catalog on read, and operation_count reflects the post-filter (kept) count.
-- NULL = no filter = pre-AF-614 behaviour (backwards compatible).
ALTER TABLE api_schemas ADD COLUMN operation_filter JSONB;
