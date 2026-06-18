-- AF-441: inline collaboration comment threads anchored to a line range of a query's SQL
-- while it is in a co-authorable state (PENDING_REVIEW). A thread is a root comment
-- (parent_comment_id IS NULL) plus replies (parent_comment_id = the root's id). Threads can
-- be resolved and reopened. Comments are persisted + audited; the live co-editing buffer
-- (a Yjs CRDT relayed opaquely over /ws) is ephemeral and is NOT stored here. Committing the
-- co-authored SQL re-enters the workflow through the existing POST /api/v1/queries path.

CREATE TYPE comment_status AS ENUM ('OPEN', 'RESOLVED');

CREATE TABLE query_comments (
    id                UUID           PRIMARY KEY,
    query_request_id  UUID           NOT NULL REFERENCES query_requests(id) ON DELETE CASCADE,
    author_id         UUID           NOT NULL REFERENCES users(id),
    -- root thread = NULL; a reply points at its root comment. ON DELETE CASCADE so resolving a
    -- query's deletion removes the whole thread tree.
    parent_comment_id UUID                    REFERENCES query_comments(id) ON DELETE CASCADE,
    anchor_start_line INTEGER        NOT NULL,
    anchor_end_line   INTEGER        NOT NULL,
    -- snapshot of the anchored SQL text at creation time, so the thread stays meaningful even
    -- after the co-authored buffer is resubmitted and the line numbers drift.
    anchor_snapshot   TEXT,
    body              TEXT           NOT NULL,
    -- status is meaningful only on the root comment; replies leave it at the default.
    status            comment_status NOT NULL DEFAULT 'OPEN',
    resolved_by       UUID                    REFERENCES users(id),
    resolved_at       TIMESTAMPTZ,
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_query_comments_anchor_range    CHECK (anchor_end_line >= anchor_start_line),
    CONSTRAINT chk_query_comments_anchor_positive CHECK (anchor_start_line >= 1)
);

CREATE INDEX idx_query_comments_query_status ON query_comments (query_request_id, status);
CREATE INDEX idx_query_comments_query_parent ON query_comments (query_request_id, parent_comment_id);
CREATE INDEX idx_query_comments_parent       ON query_comments (parent_comment_id)
    WHERE parent_comment_id IS NOT NULL;
