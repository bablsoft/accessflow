-- #622: on-behalf-of provenance for decisions taken under a review delegation. The acting reviewer
-- stays in reviewer_id; on_behalf_of_user_id names the delegator whose authority was borrowed, and
-- delegation_id pins the exact grant so a later revoke cannot destroy the evidence. Both are NULL
-- whenever the reviewer was eligible in their own right.
--
-- !! DO NOT widen the existing UNIQUE (request, reviewer_id, stage) index on these tables to
-- include on_behalf_of_user_id. Keeping it keyed on the ACTING reviewer is what guarantees one
-- human gets one vote: a delegate holding delegations from two absent approvers cannot
-- single-handedly satisfy min_approvals = 2, because the second insert collides. Adding
-- on_behalf_of_user_id to the index would let one person cast N votes and is the single most
-- dangerous change that could be made to this design.
--
-- The index does not close one related hole — delegator X votes, then delegate D votes on behalf of
-- X (different reviewer_id, so the index permits it). That is handled by an explicit service-layer
-- guard, which also covers the reverse order and returns a clean 403 rather than surfacing a
-- constraint violation as a 500.
--
-- No FKs: these are decision records that must survive deletion of either party, matching
-- reviewer_id on api_review_decisions / group_review_decisions.

ALTER TABLE review_decisions
    ADD COLUMN on_behalf_of_user_id UUID,
    ADD COLUMN delegation_id        UUID;

ALTER TABLE api_review_decisions
    ADD COLUMN on_behalf_of_user_id UUID,
    ADD COLUMN delegation_id        UUID;

ALTER TABLE group_review_decisions
    ADD COLUMN on_behalf_of_user_id UUID,
    ADD COLUMN delegation_id        UUID;

-- Oversight: "every decision taken on my behalf while I was away". Partial, because delegated
-- decisions are a small minority of all decisions.
CREATE INDEX idx_review_decisions_on_behalf_of
    ON review_decisions (on_behalf_of_user_id)
    WHERE on_behalf_of_user_id IS NOT NULL;

CREATE INDEX idx_api_review_decisions_on_behalf_of
    ON api_review_decisions (on_behalf_of_user_id)
    WHERE on_behalf_of_user_id IS NOT NULL;

CREATE INDEX idx_group_review_decisions_on_behalf_of
    ON group_review_decisions (on_behalf_of_user_id)
    WHERE on_behalf_of_user_id IS NOT NULL;
