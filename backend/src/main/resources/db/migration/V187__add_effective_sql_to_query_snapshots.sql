-- Effective executed SQL (#937): the statement as it actually ran — row-security predicates and
-- soft-delete rewrites spliced in, bound values redacted as '?'. NULL when no rewrite occurred, so
-- pre-existing rows and unrewritten queries stay honest. Frozen at execution time: a later policy
-- edit or delete never changes it.
ALTER TABLE query_snapshots ADD COLUMN effective_sql TEXT;
