CREATE TYPE decision AS ENUM ('APPROVED', 'REJECTED', 'REQUESTED_CHANGES');

CREATE TABLE review_decisions (
    id               UUID        PRIMARY KEY,
    query_request_id UUID        NOT NULL REFERENCES query_requests(id),
    reviewer_id      UUID        NOT NULL REFERENCES users(id),
    decision         decision    NOT NULL,
    comment          TEXT,
    stage            INTEGER     NOT NULL,
    decided_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
