package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.RagComponentsFactory;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.RagStoreType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Builds a {@link HelpCorpusRetriever} for one organization's help configuration, or reports that
 * retrieval is unavailable (AF-902).
 *
 * <p>Empty is a normal answer, not an error. Retrieval being off is a supported steady state — the
 * agent answers from the generated quick-reference block, which is the only mode available to an
 * install whose embedding provider cannot embed at all. Callers substitute that block; they never
 * fail a question because there is no vector store.
 *
 * <p>Components are built per call. That is deliberate for this change: the chat runtime that will
 * call this on every turn owns the caching decision, and caching a store keyed on a row that an admin
 * can re-point would need the same eviction plumbing {@code AiAnalyzerStrategyHolder} has.
 */
@Component
@RequiredArgsConstructor
public class HelpCorpusRetrieverFactory {

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusRetrieverFactory.class);

    private final AiConfigRepository aiConfigRepository;
    private final RagComponentsFactory ragComponentsFactory;
    private final PgVectorAvailability pgVectorAvailability;

    public Optional<HelpCorpusRetriever> retriever(HelpAgentConfigEntity config) {
        if (config == null || !config.isEnabled() || !config.isRetrievalEnabled()
                || config.getAiConfigId() == null) {
            return Optional.empty();
        }
        var aiConfig = aiConfigRepository
                .findByIdAndOrganizationId(config.getAiConfigId(), config.getOrganizationId())
                .orElse(null);
        if (aiConfig == null || !aiConfig.isRagEnabled() || aiConfig.getRagStoreType() == null
                || aiConfig.getEmbeddingProvider() == null
                || aiConfig.getEmbeddingProvider() == AiProviderType.ANTHROPIC) {
            log.debug("Help corpus retrieval unavailable for organization {}: the bound AI "
                    + "configuration cannot embed", config.getOrganizationId());
            return Optional.empty();
        }
        if (aiConfig.getRagStoreType() == RagStoreType.PGVECTOR
                && !pgVectorAvailability.isAvailable()) {
            log.warn("Help corpus retrieval unavailable for organization {}: the store is PGVECTOR "
                    + "but pgvector is {} on this deployment", config.getOrganizationId(),
                    pgVectorAvailability.status());
            return Optional.empty();
        }
        try {
            var embeddingModel = ragComponentsFactory.embeddingModel(aiConfig);
            var vectorStore = ragComponentsFactory.vectorStore(aiConfig, embeddingModel);
            return Optional.of(new HelpCorpusRetriever(vectorStore, config.getId(), config.getTopK(),
                    config.getSimilarityThreshold()));
        } catch (RuntimeException e) {
            log.warn("Could not build help corpus retrieval for organization {}: {}",
                    config.getOrganizationId(), e.getMessage());
            return Optional.empty();
        }
    }
}
