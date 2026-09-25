-- #942: set when an exhausted REQUIRE_REVIEW data budget forced the query into human review as it
-- left PENDING_AI. Only a query carrying this stamp may run past an exhausted budget at execution:
-- an approval given while allowance remained was never a budget escalation, so it must not lift
-- the budget once the submitter has spent the rest of it on other reads.
ALTER TABLE query_requests ADD COLUMN data_budget_review_forced BOOLEAN NOT NULL DEFAULT FALSE;
