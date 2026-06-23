package com.bablsoft.accessflow.ai.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * An additional orchestration member of a parent {@code ai_config} (AF-450). Carries its own
 * provider / model / endpoint / encrypted key + voting weight, and inherits the parent's timeout,
 * max-completion-tokens, prompt template and RAG retriever.
 */
@Entity
@Table(name = "ai_config_model")
@Getter
@Setter
@NoArgsConstructor
public class AiConfigModelEntity {

    @Id
    private UUID id;

    @Column(name = "ai_config_id", nullable = false)
    private UUID aiConfigId;

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

    @Column(nullable = false)
    private double weight = 1.0;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
