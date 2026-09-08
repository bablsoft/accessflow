-- AF-904 (epic AF-899): transcripts for the in-app documentation help chat agent.
--
-- One row per conversation in help_chat_sessions, one row per message in help_chat_messages. Both
-- carry organization_id: the session because a transcript belongs to a tenant even after the user
-- is gone from the UI, the message because the monthly AI token budget sums per organization and
-- must not join through the session to do it.
--
-- citations is jsonb written by the server from the chunks it actually retrieved, never re-derived
-- from the message text (epic AF-899 decision 6). Storing them alongside the answer is what lets a
-- reloaded conversation render the same links it rendered live, even after the corpus moves on.
--
-- sequence_number, not created_at, is the transcript's order: both messages of a turn are inserted
-- in one statement and share a timestamp, so a created_at sort could return the answer first.
--
-- corpus_version pins the assistant message to the documentation revision that produced it, so a
-- support conversation can be traced back to what the agent was actually reading.
--
-- Both FKs to help_chat_sessions / organizations / users are ON DELETE CASCADE: deleting a session,
-- an organization or a user takes its transcripts with it, and the retention job relies on the
-- session cascade to remove messages without a second statement.

CREATE TYPE help_chat_role AS ENUM ('USER', 'ASSISTANT');

CREATE TABLE help_chat_sessions (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(255),
    message_count   INTEGER     NOT NULL DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The user's own session list, newest conversation first.
CREATE INDEX help_chat_sessions_user_last_message_idx
    ON help_chat_sessions(user_id, last_message_at DESC);
-- The retention job's per-organization sweep.
CREATE INDEX help_chat_sessions_organization_created_idx
    ON help_chat_sessions(organization_id, created_at);

CREATE TABLE help_chat_messages (
    id                UUID           PRIMARY KEY,
    session_id        UUID           NOT NULL REFERENCES help_chat_sessions(id) ON DELETE CASCADE,
    organization_id   UUID           NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    sequence_number   INTEGER        NOT NULL,
    role              help_chat_role NOT NULL,
    content           TEXT           NOT NULL,
    citations         JSONB          NOT NULL DEFAULT '[]',
    corpus_version    VARCHAR(64),
    model             VARCHAR(100),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    latency_ms        INTEGER,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Replaying one conversation in order. sequence_number rather than created_at because the two
-- messages of a turn are written in the same statement: they share an instant, and ordering on it
-- would let the answer come back before its question.
CREATE UNIQUE INDEX help_chat_messages_session_sequence_idx
    ON help_chat_messages(session_id, sequence_number);
-- The monthly token-budget sum: organization + window, without touching the session table.
CREATE INDEX help_chat_messages_organization_created_idx
    ON help_chat_messages(organization_id, created_at);
