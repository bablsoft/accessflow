package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.KnowledgeDocumentView;

import java.time.Instant;
import java.util.UUID;

record KnowledgeDocumentResponse(
        UUID id,
        UUID aiConfigId,
        String title,
        int charCount,
        int chunkCount,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    static KnowledgeDocumentResponse from(KnowledgeDocumentView view) {
        return new KnowledgeDocumentResponse(view.id(), view.aiConfigId(), view.title(),
                view.charCount(), view.chunkCount(), view.status(), view.errorMessage(),
                view.createdAt(), view.updatedAt());
    }
}
