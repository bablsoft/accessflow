-- AF-446: persist the client context captured at submission so routing policies can evaluate
-- source-IP / user-agent / CI-CD-origin conditions asynchronously (after AI completion, where no
-- HTTP request exists). All nullable / defaulted for zero-downtime deploys.
ALTER TABLE query_requests
    ADD COLUMN submitted_ip         VARCHAR(45),
    ADD COLUMN submitted_user_agent TEXT,
    ADD COLUMN cicd_origin          BOOLEAN NOT NULL DEFAULT false;
