package com.bablsoft.accessflow.notifications.internal.persistence.entity;

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
 * Links an AccessFlow user to a Slack workspace user id so inbound Slack actions can be attributed
 * to the right reviewer. One mapping per user; unique per (organization, slack user) too.
 */
@Entity
@Table(name = "user_slack_mapping")
@Getter
@Setter
@NoArgsConstructor
public class UserSlackMappingEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "slack_user_id", nullable = false, length = 64)
    private String slackUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
