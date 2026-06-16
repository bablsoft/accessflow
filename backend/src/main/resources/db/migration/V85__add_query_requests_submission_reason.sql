-- AF-451: record why a query request was submitted. USER_SUBMITTED is the existing behaviour;
-- AI_SUGGESTION marks a draft created by applying an AI optimization suggestion. Defaulted so existing
-- rows and zero-downtime deploys are unaffected. CREATE TYPE + ALTER TABLE run in one transaction.
CREATE TYPE submission_reason AS ENUM ('USER_SUBMITTED', 'AI_SUGGESTION');

ALTER TABLE query_requests
    ADD COLUMN submission_reason submission_reason NOT NULL DEFAULT 'USER_SUBMITTED';
