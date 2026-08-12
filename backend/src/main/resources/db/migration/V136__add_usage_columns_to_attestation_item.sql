-- #625: attach usage evidence to each attestation item, so a reviewer certifying a grant sees
-- whether it has actually been used instead of deciding blind.
--
-- All nullable with no backfill, deliberately. attestation_item is a FROZEN evidence record of what
-- was true at campaign open; retro-stamping today's usage onto campaigns that were reviewed months
-- ago would falsify the evidence. Existing items keep NULL, and the UI renders that as "no data" —
-- which is also what a grant summarised after its campaign opened will show.

ALTER TABLE attestation_item
    ADD COLUMN usage_last_used_at           TIMESTAMPTZ,
    ADD COLUMN usage_count                  BIGINT,
    ADD COLUMN usage_granted_target_count   INT,
    ADD COLUMN usage_used_target_count      INT,
    ADD COLUMN usage_recommendation         grant_usage_recommendation;

-- Reviewer worklist ordering: never-used grants first (NULL last_used_at), then longest-idle.
CREATE INDEX idx_attestation_item_usage_staleness
    ON attestation_item (campaign_id, usage_last_used_at NULLS FIRST);
