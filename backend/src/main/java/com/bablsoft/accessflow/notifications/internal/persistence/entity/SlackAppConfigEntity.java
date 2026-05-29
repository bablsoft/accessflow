package com.bablsoft.accessflow.notifications.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-organization Slack app credentials. {@code bot_token} and {@code signing_secret} are stored
 * AES-256-GCM encrypted and never serialized.
 */
@Entity
@Table(name = "slack_app_config")
@Getter
@Setter
@NoArgsConstructor
public class SlackAppConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @JsonIgnore
    @Column(name = "bot_token_encrypted", nullable = false, columnDefinition = "text")
    private String botTokenEncrypted;

    @JsonIgnore
    @Column(name = "signing_secret_encrypted", nullable = false, columnDefinition = "text")
    private String signingSecretEncrypted;

    @Column(name = "default_channel_id", nullable = false, length = 64)
    private String defaultChannelId;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
