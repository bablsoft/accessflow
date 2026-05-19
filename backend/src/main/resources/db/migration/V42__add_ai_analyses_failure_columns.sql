-- AF-249: surface AI analysis failure to the frontend with explicit columns.
-- The previous implementation persisted a sentinel row with risk_level=CRITICAL and
-- summary='AI analysis failed: <reason>'; this is fragile to parse on the client and
-- conflates real CRITICAL risk with analyzer failure. The new flag is the source of
-- truth going forward; legacy rows retain failed=FALSE and the stringy summary.

ALTER TABLE ai_analyses
    ADD COLUMN failed        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN error_message TEXT;
