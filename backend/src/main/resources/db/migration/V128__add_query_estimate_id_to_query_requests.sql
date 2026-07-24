ALTER TABLE query_requests
    ADD COLUMN query_estimate_id UUID;

ALTER TABLE query_requests
    ADD CONSTRAINT fk_query_requests_query_estimate
    FOREIGN KEY (query_estimate_id) REFERENCES query_estimates(id);
