package com.bablsoft.accessflow.ai.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.api.VotingStrategy;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "system_prompt_template", columnDefinition = "text")
    private String systemPromptTemplate;

    @Column(name = "langfuse_prompt_name", length = 255)
    private String langfusePromptName;

    @Column(name = "langfuse_prompt_label", length = 255)
    private String langfusePromptLabel;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 30_000;

    @Column(name = "max_prompt_tokens", nullable = false)
    private int maxPromptTokens = 8_000;

    @Column(name = "max_completion_tokens", nullable = false)
    private int maxCompletionTokens = 2_000;

    // --- RAG knowledge base (AF-336) ---

    @Column(name = "rag_enabled", nullable = false)
    private boolean ragEnabled = false;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "rag_store_type", columnDefinition = "rag_store_type")
    private RagStoreType ragStoreType;

    @Column(name = "rag_top_k", nullable = false)
    private int ragTopK = 4;

    @Column(name = "rag_similarity_threshold", nullable = false)
    private double ragSimilarityThreshold = 0.5;

    @Column(name = "rag_endpoint", length = 500)
    private String ragEndpoint;

    @Column(name = "rag_collection", length = 255)
    private String ragCollection;

    @JsonIgnore
    @Column(name = "rag_api_key_encrypted", columnDefinition = "text")
    private String ragApiKeyEncrypted;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "embedding_provider", columnDefinition = "ai_provider")
    private AiProviderType embeddingProvider;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "embedding_endpoint", length = 500)
    private String embeddingEndpoint;

    @JsonIgnore
    @Column(name = "embedding_api_key_encrypted", columnDefinition = "text")
    private String embeddingApiKeyEncrypted;

    // --- Multi-model orchestration + guardrails (AF-450) ---

    @Column(name = "orchestration_enabled", nullable = false)
    private boolean orchestrationEnabled = false;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "voting_strategy", nullable = false, columnDefinition = "voting_strategy")
    private VotingStrategy votingStrategy = VotingStrategy.WEIGHTED_AVERAGE;

    @Column(name = "voting_weight", nullable = false)
    private double votingWeight = 1.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guardrail_patterns", nullable = false, columnDefinition = "jsonb")
    private String guardrailPatterns = "[]";

    // --- Provider fallback pool (AF-458): NULL = not a fallback; lower value = tried first ---

    @Column(name = "fallback_priority")
    private Integer fallbackPriority;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
