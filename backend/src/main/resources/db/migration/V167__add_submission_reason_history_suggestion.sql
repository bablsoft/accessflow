-- #776: automatic query suggestions. A new submission reason marks a draft the analyst
-- loaded from the mined-history suggestions panel, keeping it distinct from AI_SUGGESTION,
-- which already means "applied an AI optimization suggestion" (#451 / #498). Adding a value
-- to an existing PG enum cannot run inside a transaction block, so this migration is paired
-- with a .sql.conf setting executeInTransaction=false and is isolated from the table
-- creation in V166 (V93 / V133 precedent).
ALTER TYPE submission_reason ADD VALUE IF NOT EXISTS 'HISTORY_SUGGESTION';
