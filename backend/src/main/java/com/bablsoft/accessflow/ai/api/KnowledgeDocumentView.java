package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for a RAG knowledge-base document (AF-336). The raw {@code content} is intentionally
 * omitted — list responses carry only metadata. {@code status} is {@code INDEXED} or {@code FAILED}.
 */
public record KnowledgeDocumentView(
        UUID id,
        UUID aiConfigId,
        String title,
        int charCount,
        int chunkCount,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {
}
