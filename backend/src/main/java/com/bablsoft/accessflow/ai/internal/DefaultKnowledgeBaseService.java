package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.AiConfigRagInvalidException;
import com.bablsoft.accessflow.ai.api.CreateKnowledgeDocumentCommand;
import com.bablsoft.accessflow.ai.api.KnowledgeBaseService;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentIngestException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentNotFoundException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentView;
import com.bablsoft.accessflow.ai.api.RagConnectionTestResult;
import com.bablsoft.accessflow.ai.internal.config.RagProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.KnowledgeDocumentEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.KnowledgeDocumentRepository;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.RagStoreType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultKnowledgeBaseService implements KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeBaseService.class);
    private static final String PROBE_TEXT = "AccessFlow RAG connectivity probe";

    private final KnowledgeDocumentRepository documentRepository;
    private final AiConfigRepository aiConfigRepository;
    private final RagComponentsFactory ragComponentsFactory;
    private final RagProperties ragProperties;
    private final PgVectorAvailability pgVectorAvailability;

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentView> list(UUID aiConfigId, UUID organizationId) {
        requireConfig(aiConfigId, organizationId);
        return documentRepository.findAllByAiConfigIdOrderByCreatedAtDesc(aiConfigId).stream()
                .map(DefaultKnowledgeBaseService::toView)
                .toList();
    }

    @Override
    @Transactional
    public KnowledgeDocumentView create(UUID aiConfigId, UUID organizationId,
                                        CreateKnowledgeDocumentCommand command) {
        var config = requireRagEnabledConfig(aiConfigId, organizationId);
        requirePgVectorAvailable(config);
        var title = command.title() == null ? null : command.title().trim();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Knowledge document title is required");
        }
        var content = command.content();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Knowledge document content is required");
        }
        if (content.length() > ragProperties.maxDocumentChars()) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.document_too_large");
        }
        var docId = UUID.randomUUID();
        int chunkCount = ingest(config, docId, organizationId, title, content);

        var entity = new KnowledgeDocumentEntity();
        entity.setId(docId);
        entity.setAiConfigId(aiConfigId);
        entity.setOrganizationId(organizationId);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setCharCount(content.length());
        entity.setChunkCount(chunkCount);
        entity.setStatus(KnowledgeDocumentEntity.STATUS_INDEXED);
        var now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toView(documentRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID aiConfigId, UUID organizationId) {
        var config = requireConfig(aiConfigId, organizationId);
        var doc = documentRepository.findByIdAndAiConfigIdAndOrganizationId(id, aiConfigId, organizationId)
                .orElseThrow(() -> new KnowledgeDocumentNotFoundException(id));
        if (isRagBuildable(config)) {
            try {
                var embeddingModel = ragComponentsFactory.embeddingModel(config);
                var vectorStore = ragComponentsFactory.vectorStore(config, embeddingModel);
                vectorStore.delete("document_id == '" + id + "'");
            } catch (RuntimeException e) {
                // Best-effort: a failed vector delete must not strand the document row.
                log.warn("Failed to delete RAG vectors for document {}: {}", id, e.getMessage());
            }
        }
        documentRepository.delete(doc);
    }

    @Override
    @Transactional(readOnly = true)
    public RagConnectionTestResult testConnection(UUID aiConfigId, UUID organizationId) {
        var config = requireRagEnabledConfig(aiConfigId, organizationId);
        requirePgVectorAvailable(config);
        try {
            var embeddingModel = ragComponentsFactory.embeddingModel(config);
            var probe = embeddingModel.embed(PROBE_TEXT);
            var dimensions = probe.length;
            var vectorStore = ragComponentsFactory.vectorStore(config, embeddingModel);
            vectorStore.similaritySearch(SearchRequest.builder().query(PROBE_TEXT).topK(1).build());
            if (config.getRagStoreType() == RagStoreType.PGVECTOR
                    && dimensions != ragProperties.pgvectorDimensions()) {
                return RagConnectionTestResult.error("Embedding dimension " + dimensions
                        + " does not match the pgvector column dimension "
                        + ragProperties.pgvectorDimensions());
            }
            return RagConnectionTestResult.ok("Embedding model and vector store are reachable",
                    dimensions);
        } catch (RuntimeException e) {
            log.warn("RAG connectivity test failed for ai_config={}: {}", aiConfigId, e.getMessage());
            return RagConnectionTestResult.error(e.getMessage());
        }
    }

    private int ingest(AiConfigEntity config, UUID docId, UUID organizationId, String title,
                       String content) {
        try {
            var embeddingModel = ragComponentsFactory.embeddingModel(config);
            var vectorStore = ragComponentsFactory.vectorStore(config, embeddingModel);
            var chunks = chunk(content, config.getId(), docId, organizationId, title);
            vectorStore.add(chunks);
            return chunks.size();
        } catch (RuntimeException e) {
            log.error("RAG ingestion failed for ai_config={} title='{}': {}",
                    config.getId(), title, e.getMessage(), e);
            throw new KnowledgeDocumentIngestException("RAG ingestion failed", e);
        }
    }

    private List<Document> chunk(String content, UUID aiConfigId, UUID docId, UUID organizationId,
                                 String title) {
        Map<String, Object> metadata = Map.of(
                "ai_config_id", aiConfigId.toString(),
                "document_id", docId.toString(),
                "organization_id", organizationId.toString(),
                "title", title);
        var document = new Document(content, metadata);
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(ragProperties.chunkSize())
                .build();
        return splitter.apply(List.of(document));
    }

    private boolean isRagBuildable(AiConfigEntity config) {
        if (!config.isRagEnabled() || config.getRagStoreType() == null
                || config.getEmbeddingProvider() == null) {
            return false;
        }
        return config.getRagStoreType() != RagStoreType.PGVECTOR || pgVectorAvailability.isAvailable();
    }

    private void requirePgVectorAvailable(AiConfigEntity config) {
        if (config.getRagStoreType() == RagStoreType.PGVECTOR && !pgVectorAvailability.isAvailable()) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.pgvector_unavailable");
        }
    }

    private AiConfigEntity requireConfig(UUID aiConfigId, UUID organizationId) {
        return aiConfigRepository.findByIdAndOrganizationId(aiConfigId, organizationId)
                .orElseThrow(() -> new AiConfigNotFoundException(aiConfigId));
    }

    private AiConfigEntity requireRagEnabledConfig(UUID aiConfigId, UUID organizationId) {
        var config = requireConfig(aiConfigId, organizationId);
        if (!config.isRagEnabled()) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.not_enabled");
        }
        return config;
    }

    private static KnowledgeDocumentView toView(KnowledgeDocumentEntity e) {
        return new KnowledgeDocumentView(e.getId(), e.getAiConfigId(), e.getTitle(), e.getCharCount(),
                e.getChunkCount(), e.getStatus(), e.getErrorMessage(), e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
