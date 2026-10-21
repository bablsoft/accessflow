-- #990 (epic #989): Deployment change tickets — persistence foundation. Stores the flag and the
-- ticket rows only; ticket creation, the inbound decision sync and the gate conjunct land in later
-- sub-issues, so this migration changes no behaviour on its own.
--
-- deployment_tickets is the deploygov sibling of query_tickets (V119), never a widened copy of it:
-- deployment_request_id is a real FK (the deployment_review_decisions precedent, V150), while
-- organization_id and channel_id are bare UUIDs (the V149 cross-module convention) so deleting a
-- notification channel never takes the change evidence with it. The dedupe key is
-- (channel_id, deployment_request_id) with no trigger dimension — one change ticket per deployment
-- per channel. decision (APPROVED / REJECTED / NULL) + decided_at are stamped by the inbound sync
-- on every verified callback; plain text like ticket_system, deliberately not a pg enum.

ALTER TABLE deployment_environments
    ADD COLUMN change_ticket_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE deployment_tickets (
    id                    UUID         PRIMARY KEY,
    organization_id       UUID         NOT NULL,
    deployment_request_id UUID         NOT NULL REFERENCES deployment_requests(id) ON DELETE CASCADE,
    channel_id            UUID         NOT NULL,
    ticket_system         VARCHAR(20)  NOT NULL,
    trigger_event         VARCHAR(40)  NOT NULL,
    external_id           VARCHAR(255) NOT NULL,
    external_key          VARCHAR(255) NOT NULL,
    url                   TEXT,
    status                VARCHAR(100) NOT NULL,
    resolution            VARCHAR(100),
    decision              VARCHAR(20),
    decided_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_deployment_tickets_channel_request UNIQUE (channel_id, deployment_request_id),
    CONSTRAINT uq_deployment_tickets_channel_external UNIQUE (channel_id, external_id)
);

-- Deployment detail page and the gate: all tickets linked to a deployment request.
CREATE INDEX idx_deployment_tickets_request ON deployment_tickets (deployment_request_id);
