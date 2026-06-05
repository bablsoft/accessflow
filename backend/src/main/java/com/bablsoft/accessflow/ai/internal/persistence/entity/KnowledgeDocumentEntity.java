package com.bablsoft.accessflow.ai.internal.persistence.entity;

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
 * A RAG knowledge-base document attached to an {@code ai_config} (AF-336). The raw {@code content}
 * is the admin-managed source of truth; on ingestion it is chunked, embedded and upserted into the
 * configured vector store, with {@code chunkCount} / {@code status} recording the outcome.
 */
@Entity
@Table(name = "knowledge_document")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeDocumentEntity {

    /** Successfully chunked, embedded and stored. */
    public static final String STATUS_INDEXED = "INDEXED";
    /** Ingestion failed (see {@code errorMessage}). */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    private UUID id;

    @Column(name = "ai_config_id", nullable = false)
    private UUID aiConfigId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(nullable = false, length = 20)
    private String status = STATUS_INDEXED;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
