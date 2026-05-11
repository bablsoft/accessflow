package com.partqam.accessflow.ai.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.partqam.accessflow.core.api.AiProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_config")
@Getter
@Setter
@NoArgsConstructor
public class AiConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "ai_provider")
    private AiProviderType provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(length = 500)
    private String endpoint;

    @JsonIgnore
    @Column(name = "api_key_encrypted", columnDefinition = "text")
    private String apiKeyEncrypted;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 30_000;

    @Column(name = "max_prompt_tokens", nullable = false)
    private int maxPromptTokens = 8_000;

    @Column(name = "max_completion_tokens", nullable = false)
    private int maxCompletionTokens = 2_000;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
