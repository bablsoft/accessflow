-- AF-453: ServiceNow & Jira ticketing integration. One row per ticket auto-created in an external
-- ticketing system for a workflow event on a query (rejected / escalated / review-timeout). The
-- (channel_id, query_request_id, trigger_event) UNIQUE is the create-once dedupe backstop; the
-- (channel_id, external_id) UNIQUE lets the inbound status webhook resolve a ticket unambiguously.
-- status / resolution hold the external system's raw values, updated by the signed inbound webhook.

CREATE TABLE query_tickets (
    id               UUID         PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    query_request_id UUID         NOT NULL REFERENCES query_requests(id) ON DELETE CASCADE,
    channel_id       UUID         NOT NULL REFERENCES notification_channels(id) ON DELETE CASCADE,
    ticket_system    VARCHAR(20)  NOT NULL,
    trigger_event    VARCHAR(40)  NOT NULL,
    external_id      VARCHAR(255) NOT NULL,
    external_key     VARCHAR(255) NOT NULL,
    url              TEXT,
    status           VARCHAR(100) NOT NULL,
    resolution       VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_query_tickets_channel_query_trigger UNIQUE (channel_id, query_request_id, trigger_event),
    CONSTRAINT uq_query_tickets_channel_external UNIQUE (channel_id, external_id)
);

-- Query detail page: list all tickets linked to a query.
CREATE INDEX idx_query_tickets_query_request ON query_tickets (query_request_id);
