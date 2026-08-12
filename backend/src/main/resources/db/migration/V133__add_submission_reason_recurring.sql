-- #627: recurring approved queries. A new submission reason marks the child occurrence rows the
-- RecurringQueryRunJob creates for an approved recurring series. Adding a value to an existing PG
-- enum cannot run inside a transaction block, so this migration is paired with a .sql.conf setting
-- executeInTransaction=false and is isolated from the column changes in V132 (V93 precedent).
ALTER TYPE submission_reason ADD VALUE IF NOT EXISTS 'RECURRING';
