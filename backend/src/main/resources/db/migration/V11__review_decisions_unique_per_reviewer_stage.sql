CREATE UNIQUE INDEX uq_review_decisions_per_reviewer_stage
    ON review_decisions (query_request_id, reviewer_id, stage);
