package com.bablsoft.accessflow.core.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket auto-created in an external ticketing system (ServiceNow / Jira, AF-453) for a workflow
 * event on a query. {@code ticketSystem} / {@code triggerEvent} are plain strings — the channel and
 * event enums live in the notifications module, which core cannot depend on.
 */
@Entity
@Table(name = "query_tickets")
@Getter
@Setter
@NoArgsConstructor
public class QueryTicketEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "query_request_id", nullable = false)
    private UUID queryRequestId;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "ticket_system", nullable = false, length = 20)
    private String ticketSystem;

    @Column(name = "trigger_event", nullable = false, length = 40)
    private String triggerEvent;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "external_key", nullable = false)
    private String externalKey;

    @Column(columnDefinition = "text")
    private String url;

    @Column(nullable = false, length = 100)
    private String status;

    @Column(length = 100)
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
