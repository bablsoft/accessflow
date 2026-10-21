package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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
 * A change ticket opened in an external ticketing system (ServiceNow / Jira) for a deployment
 * request (#990) — the deploygov sibling of core's {@code QueryTicketEntity}. {@code ticketSystem},
 * {@code triggerEvent} and {@code decision} are plain strings — the channel and event enums live in
 * the notifications module, which deploygov cannot depend on.
 */
@Entity
@Table(name = "deployment_tickets")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentTicketEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "deployment_request_id", nullable = false)
    private UUID deploymentRequestId;

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

    @Column(length = 20)
    private String decision;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
