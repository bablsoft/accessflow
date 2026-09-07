package com.bablsoft.accessflow.ai.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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
 * Per-organization in-app help chat agent settings (one row per org, AF-901). Every column defaults
 * to a working configuration except {@code enabled} and {@code aiConfigId}, so a fresh row is inert
 * until an admin binds a model and turns it on.
 *
 * <p>{@code aiConfigId} is a plain column rather than a {@code @ManyToOne}: the FK is
 * {@code ON DELETE SET NULL}, so losing the bound configuration disables help chat instead of
 * blocking the delete, and nothing here ever needs the {@code ai_config} row eagerly.
 */
@Entity
@Table(name = "help_agent_config")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class HelpAgentConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "ai_config_id")
    private UUID aiConfigId;

    @Column(name = "retrieval_enabled", nullable = false)
    private boolean retrievalEnabled = true;

    @Column(name = "top_k", nullable = false)
    private int topK = 6;

    @Column(name = "similarity_threshold", nullable = false)
    private double similarityThreshold = 0.4;

    @Column(name = "max_history_turns", nullable = false)
    private int maxHistoryTurns = 8;

    @Column(name = "max_question_chars", nullable = false)
    private int maxQuestionChars = 2000;

    @Column(name = "send_user_context", nullable = false)
    private boolean sendUserContext = true;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays = 90;

    @Column(name = "per_user_requests_per_minute", nullable = false)
    private int perUserRequestsPerMinute = 6;

    /** Content-derived corpus version currently ingested for this org; {@code null} = never indexed. */
    @Column(name = "indexed_corpus_version", length = 64)
    private String indexedCorpusVersion;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "index_error", columnDefinition = "text")
    private String indexError;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
