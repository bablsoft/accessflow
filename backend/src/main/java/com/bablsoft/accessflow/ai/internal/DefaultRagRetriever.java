package com.bablsoft.accessflow.ai.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retrieves the top-K most similar knowledge-base chunks for a query from the config's vector store
 * and joins them into a prompt-ready context block. Results are scoped to this {@code ai_config} via
 * an {@code ai_config_id} metadata filter so a shared store serves many configs. Retrieval failures
 * are swallowed (logged) and degrade to {@code null} — analysis is never blocked by RAG.
 */
class DefaultRagRetriever implements RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(DefaultRagRetriever.class);
    private static final String CHUNK_SEPARATOR = "\n\n---\n\n";

    private final VectorStore vectorStore;
    private final UUID aiConfigId;
    private final int topK;
    private final double similarityThreshold;

    DefaultRagRetriever(VectorStore vectorStore, UUID aiConfigId, int topK, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.aiConfigId = aiConfigId;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public String retrieve(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            var request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression("ai_config_id == '" + aiConfigId + "'")
                    .build();
            var docs = vectorStore.similaritySearch(request);
            if (docs == null || docs.isEmpty()) {
                return null;
            }
            var joined = docs.stream()
                    .map(Document::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining(CHUNK_SEPARATOR));
            return joined.isBlank() ? null : joined;
        } catch (RuntimeException e) {
            log.warn("RAG retrieval failed for ai_config={}: {}", aiConfigId, e.getMessage());
            return null;
        }
    }
}
