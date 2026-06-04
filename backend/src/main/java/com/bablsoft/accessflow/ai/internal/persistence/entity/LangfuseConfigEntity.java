package com.bablsoft.accessflow.ai.internal.persistence.entity;

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
 * Per-organization Langfuse integration settings (one row per org). Drives both LLM-call tracing
 * (the ingestion API) and runtime prompt management. The secret key is AES-256-GCM encrypted before
 * persistence and {@link JsonIgnore}-d so it is never serialized in any response.
 */
@Entity
@Table(name = "langfuse_config")
@Getter
@Setter
@NoArgsConstructor
public class LangfuseConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(length = 500)
    private String host;

    @Column(name = "public_key", length = 255)
    private String publicKey;

    @JsonIgnore
    @Column(name = "secret_key_encrypted", columnDefinition = "text")
    private String secretKeyEncrypted;

    @Column(name = "tracing_enabled", nullable = false)
    private boolean tracingEnabled = true;

    @Column(name = "prompt_management_enabled", nullable = false)
    private boolean promptManagementEnabled = false;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
