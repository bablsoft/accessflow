-- AF-385: break-glass / emergency access. A new submission reason marks a query that bypassed
-- pre-approval through the break-glass path. Adding a value to an existing PG enum cannot run inside
-- a transaction block, so this migration is paired with a .sql.conf setting executeInTransaction=false
-- and is isolated from the table/column changes in V94/V95.
ALTER TYPE submission_reason ADD VALUE IF NOT EXISTS 'EMERGENCY_ACCESS';
