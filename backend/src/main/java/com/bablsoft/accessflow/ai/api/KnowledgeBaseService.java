package com.bablsoft.accessflow.ai.api;

import java.util.List;
import java.util.UUID;

/**
 * Manages the RAG knowledge base attached to an {@code ai_config} (AF-336). Documents are
 * org-scoped and bound to a single AI configuration that must have RAG enabled. Ingestion chunks,
 * embeds and stores the content in the configured vector store; deletion removes the stored chunks.
 */
public interface KnowledgeBaseService {

    List<KnowledgeDocumentView> list(UUID aiConfigId, UUID organizationId);

    KnowledgeDocumentView create(UUID aiConfigId, UUID organizationId,
                                 CreateKnowledgeDocumentCommand command);

    void delete(UUID id, UUID aiConfigId, UUID organizationId);

    /** Embeds a probe and runs a similarity search to verify embedding + vector-store connectivity. */
    RagConnectionTestResult testConnection(UUID aiConfigId, UUID organizationId);
}
