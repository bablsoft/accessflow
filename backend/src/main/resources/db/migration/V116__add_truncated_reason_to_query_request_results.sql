-- Issue #49: distinguish why a stored SELECT snapshot was cut short (ROW_LIMIT vs BYTE_LIMIT).
ALTER TABLE query_request_results ADD COLUMN truncated_reason text;
