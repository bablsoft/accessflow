package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.HelpAgentConfigInvalidException;
import com.bablsoft.accessflow.ai.api.HelpAgentConfigService;
import com.bablsoft.accessflow.ai.api.HelpAgentConfigView;
import com.bablsoft.accessflow.ai.api.HelpAgentConnectionTestResult;
import com.bablsoft.accessflow.ai.api.HelpCorpusUnavailableException;
import com.bablsoft.accessflow.ai.api.UpdateHelpAgentConfigCommand;
import com.bablsoft.accessflow.ai.internal.HelpAgentConfigUpdatedEvent;
import com.bablsoft.accessflow.ai.internal.RagComponentsFactory;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderCapabilities;
import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.RagStoreType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists and validates the in-app help agent's per-organization settings (AF-901).
 *
 * <p>Reads never fail: an organization with no row is served defaults, so the admin UI has nothing
 * to create before it can render a form. Writes are where the guarantees live — enabling the agent
 * is refused unless the bound configuration can actually answer, and every refusal names its own
 * cause, including which of the three pgvector states is the problem.
 */
@Service
@RequiredArgsConstructor
public class DefaultHelpAgentConfigService implements HelpAgentConfigService {

    private static final Logger log = LoggerFactory.getLogger(DefaultHelpAgentConfigService.class);
    private static final String PROBE_TEXT = "AccessFlow help agent connectivity probe";

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;
    private static final int MIN_HISTORY_TURNS = 1;
    private static final int MAX_HISTORY_TURNS = 50;
    private static final int MIN_QUESTION_CHARS = 100;
    private static final int MAX_QUESTION_CHARS = 10_000;
    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 3650;
    private static final int MIN_REQUESTS_PER_MINUTE = 1;
    private static final int MAX_REQUESTS_PER_MINUTE = 120;

    private final HelpAgentConfigRepository repository;
    private final HelpCorpusBundle helpCorpusBundle;
    private final HelpCorpusIndexDispatcher indexDispatcher;
    private final AiConfigRepository aiConfigRepository;
    private final RagComponentsFactory ragComponentsFactory;
    private final PgVectorAvailability pgVectorAvailability;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public HelpAgentConfigView getOrDefault(UUID organizationId) {
        return repository.findByOrganizationId(organizationId)
                .map(this::toView)
                .orElseGet(() -> defaultView(organizationId));
    }

    @Override
    @Transactional
    public HelpAgentConfigView update(UUID organizationId, UpdateHelpAgentConfigCommand command) {
        var entity = repository.findByOrganizationId(organizationId)
                .orElseGet(() -> seed(organizationId));
        var previousEnabled = entity.isEnabled();
        var previousAiConfigId = entity.getAiConfigId();
        var previousRetrieval = entity.isRetrievalEnabled();

        applyBinding(entity, command, organizationId);
        applyTunables(entity, command);
        if (command.enabled() != null) {
            entity.setEnabled(command.enabled());
        }
        validateRanges(entity);
        if (entity.isEnabled()) {
            validateEnableable(entity, organizationId,
                    turnsRetrievalOn(entity, previousEnabled, previousAiConfigId, previousRetrieval));
        }

        entity.setUpdatedAt(Instant.now());
        var saved = repository.save(entity);
        eventPublisher.publishEvent(new HelpAgentConfigUpdatedEvent(
                organizationId,
                saved.getId(),
                saved.isEnabled(),
                saved.getAiConfigId(),
                saved.isRetrievalEnabled(),
                !Objects.equals(previousAiConfigId, saved.getAiConfigId())
                        || previousRetrieval != saved.isRetrievalEnabled()));
        return toView(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public HelpAgentConnectionTestResult testConnection(UUID organizationId) {
        var entity = repository.findByOrganizationId(organizationId).orElse(null);
        if (entity == null || entity.getAiConfigId() == null) {
            return HelpAgentConnectionTestResult.error(message("help_agent.test.not_configured"));
        }
        if (!entity.isRetrievalEnabled()) {
            // A supported steady state, not a failure — there is simply nothing to reach.
            return HelpAgentConnectionTestResult.notApplicable(
                    message("help_agent.test.retrieval_disabled"));
        }
        var config = aiConfigRepository.findByIdAndOrganizationId(entity.getAiConfigId(), organizationId)
                .orElse(null);
        if (config == null) {
            return HelpAgentConnectionTestResult.error(message("help_agent.test.not_configured"));
        }
        try {
            requireRetrievableConfig(config);
        } catch (HelpAgentConfigInvalidException e) {
            return HelpAgentConnectionTestResult.error(message(e.messageKey()));
        }
        try {
            // One embed for both answers the test needs — the dimension and "the model is up".
            var embeddingModel = ragComponentsFactory.embeddingModel(config);
            var dimensions = embeddingModel.embed(PROBE_TEXT).length;
            if (config.getRagStoreType() == RagStoreType.PGVECTOR
                    && dimensions != ragComponentsFactory.pgvectorDimensions()) {
                return HelpAgentConnectionTestResult.error(
                        message("error.help_agent.pgvector_dimension_mismatch"));
            }
            var vectorStore = ragComponentsFactory.vectorStore(config, embeddingModel);
            vectorStore.similaritySearch(SearchRequest.builder().query(PROBE_TEXT).topK(1).build());
            return HelpAgentConnectionTestResult.ok(message("help_agent.test.success"), dimensions);
        } catch (RuntimeException e) {
            log.warn("Help agent retrieval test failed for org {}: {}", organizationId, e.getMessage());
            // The provider's own words are the useful diagnostic here, but they can be absent.
            return HelpAgentConnectionTestResult.error(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void requestReindex(UUID organizationId) {
        var entity = repository.findByOrganizationId(organizationId).orElse(null);
        if (entity == null) {
            log.info("Help agent re-index requested for org {} but no configuration exists",
                    organizationId);
            return;
        }
        // Forced: an admin pressing re-index has a reason the version compare cannot see — a store
        // truncated out of band, a suspected partial pass. Answering "already up to date" would make
        // the button useless in exactly the situation it exists for.
        log.info("Help agent re-index requested for org {}", organizationId);
        indexDispatcher.dispatchOne(entity.getId(), true);
    }

    private void applyBinding(HelpAgentConfigEntity entity, UpdateHelpAgentConfigCommand command,
                             UUID organizationId) {
        if (command.clearAiConfig()) {
            entity.setAiConfigId(null);
        } else if (command.aiConfigId() != null) {
            // Checked even when the agent stays disabled: an unchecked id would otherwise reach the
            // FK as a 500, or — for a real row in another organization — persist a cross-tenant
            // pointer that GET echoes back.
            if (!aiConfigRepository.existsByIdAndOrganizationId(command.aiConfigId(), organizationId)) {
                throw new AiConfigNotFoundException(command.aiConfigId());
            }
            entity.setAiConfigId(command.aiConfigId());
        }
        if (command.retrievalEnabled() != null) {
            entity.setRetrievalEnabled(command.retrievalEnabled());
        }
        if (command.sendUserContext() != null) {
            entity.setSendUserContext(command.sendUserContext());
        }
    }

    private void applyTunables(HelpAgentConfigEntity entity, UpdateHelpAgentConfigCommand command) {
        if (command.topK() != null) {
            entity.setTopK(command.topK());
        }
        if (command.similarityThreshold() != null) {
            entity.setSimilarityThreshold(command.similarityThreshold());
        }
        if (command.maxHistoryTurns() != null) {
            entity.setMaxHistoryTurns(command.maxHistoryTurns());
        }
        if (command.maxQuestionChars() != null) {
            entity.setMaxQuestionChars(command.maxQuestionChars());
        }
        if (command.retentionDays() != null) {
            entity.setRetentionDays(command.retentionDays());
        }
        if (command.perUserRequestsPerMinute() != null) {
            entity.setPerUserRequestsPerMinute(command.perUserRequestsPerMinute());
        }
    }

    private void validateRanges(HelpAgentConfigEntity entity) {
        requireRange(entity.getTopK(), MIN_TOP_K, MAX_TOP_K, "error.help_agent.top_k_range");
        if (entity.getSimilarityThreshold() < 0 || entity.getSimilarityThreshold() > 1) {
            throw new HelpAgentConfigInvalidException("error.help_agent.threshold_range");
        }
        requireRange(entity.getMaxHistoryTurns(), MIN_HISTORY_TURNS, MAX_HISTORY_TURNS,
                "error.help_agent.max_history_turns_range");
        requireRange(entity.getMaxQuestionChars(), MIN_QUESTION_CHARS, MAX_QUESTION_CHARS,
                "error.help_agent.max_question_chars_range");
        requireRange(entity.getRetentionDays(), MIN_RETENTION_DAYS, MAX_RETENTION_DAYS,
                "error.help_agent.retention_days_range");
        requireRange(entity.getPerUserRequestsPerMinute(), MIN_REQUESTS_PER_MINUTE,
                MAX_REQUESTS_PER_MINUTE, "error.help_agent.requests_per_minute_range");
    }

    private static void requireRange(int value, int min, int max, String messageKey) {
        if (value < min || value > max) {
            throw new HelpAgentConfigInvalidException(messageKey);
        }
    }

    /**
     * Does this save actually turn retrieval on? Only a transition is worth an outbound embedding
     * call: re-probing on every save would mean an admin cannot change {@code retention_days} on an
     * already-enabled agent while the embedding provider is having a blip.
     */
    private static boolean turnsRetrievalOn(HelpAgentConfigEntity entity, boolean previousEnabled,
                                            UUID previousAiConfigId, boolean previousRetrieval) {
        if (!entity.isRetrievalEnabled()) {
            return false;
        }
        return !previousEnabled
                || !previousRetrieval
                || !Objects.equals(previousAiConfigId, entity.getAiConfigId());
    }

    /**
     * Enabling is refused unless the bound configuration can actually answer a question. The
     * structural checks are free and always run; the live dimension probe is an outbound call, so it
     * runs only when this save is the one turning retrieval on.
     */
    private void validateEnableable(HelpAgentConfigEntity entity, UUID organizationId,
                                    boolean probeDimensions) {
        if (!helpCorpusBundle.available()) {
            // Checked before the binding, because no binding can fix it: with no corpus the agent has
            // nothing to answer from, whether or not retrieval is on.
            throw new HelpCorpusUnavailableException("error.help_agent.corpus_missing",
                    helpCorpusBundle.loadError());
        }
        if (entity.getAiConfigId() == null) {
            throw new HelpAgentConfigInvalidException("error.help_agent.ai_config_required");
        }
        var config = aiConfigRepository.findByIdAndOrganizationId(entity.getAiConfigId(), organizationId)
                .orElseThrow(() -> new AiConfigNotFoundException(entity.getAiConfigId()));
        if (!entity.isRetrievalEnabled()) {
            return;
        }
        requireRetrievableConfig(config);
        if (probeDimensions && config.getRagStoreType() == RagStoreType.PGVECTOR) {
            requireMatchingDimension(config);
        }
    }

    /**
     * The structural half of "can this configuration retrieve" — no outbound calls. The three
     * pgvector states have three different fixes, and none of them is discoverable from the error an
     * admin would otherwise meet at their first question, so each gets its own message key.
     */
    private void requireRetrievableConfig(AiConfigEntity config) {
        if (!config.isRagEnabled() || config.getRagStoreType() == null) {
            throw new HelpAgentConfigInvalidException("error.help_agent.rag_not_enabled");
        }
        if (config.getEmbeddingProvider() == null) {
            throw new HelpAgentConfigInvalidException("error.help_agent.embedding_provider_required");
        }
        if (!AiProviderCapabilities.supportsEmbedding(config.getEmbeddingProvider())) {
            throw new HelpAgentConfigInvalidException("error.help_agent.embedding_provider_invalid");
        }
        if (config.getRagStoreType() != RagStoreType.PGVECTOR) {
            return;
        }
        switch (pgVectorAvailability.status()) {
            case AVAILABLE -> { /* usable */ }
            case DISABLED -> throw new HelpAgentConfigInvalidException(
                    "error.help_agent.pgvector_disabled");
            case EXTENSION_MISSING -> throw new HelpAgentConfigInvalidException(
                    "error.help_agent.pgvector_extension_missing");
        }
    }

    /** The one outbound call on the write path: the embedding model must match the {@code vector(N)} column. */
    private void requireMatchingDimension(AiConfigEntity config) {
        int actual;
        try {
            actual = ragComponentsFactory.embeddingModel(config).embed(PROBE_TEXT).length;
        } catch (RuntimeException e) {
            log.warn("Help agent embedding probe failed for ai_config={}: {}", config.getId(),
                    e.getMessage());
            throw new HelpAgentConfigInvalidException("error.help_agent.embedding_unreachable");
        }
        if (actual != ragComponentsFactory.pgvectorDimensions()) {
            throw new HelpAgentConfigInvalidException("error.help_agent.pgvector_dimension_mismatch");
        }
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    /**
     * Resolves the indexer's stored failure into the reader's language. The indexer runs on a
     * background thread with no request locale and the row outlives its pass, so it stores a message
     * key and its arguments rather than a sentence; this is where that becomes readable. A value that
     * is not an encoded key — written by an older build, or edited by hand — is passed through as-is,
     * because a stale reason still beats a blank field.
     */
    private String localizedIndexError(String stored) {
        var decoded = HelpIndexError.decode(stored);
        if (decoded == null) {
            return stored;
        }
        return messageSource.getMessage(decoded.messageKey(), decoded.args().toArray(),
                LocaleContextHolder.getLocale());
    }

    private HelpAgentConfigEntity seed(UUID organizationId) {
        var entity = new HelpAgentConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        return entity;
    }

    /** The view a never-configured org gets: real defaults, but no id and no invented timestamps. */
    private HelpAgentConfigView defaultView(UUID organizationId) {
        var defaults = new HelpAgentConfigEntity();
        defaults.setOrganizationId(organizationId);
        defaults.setCreatedAt(null);
        defaults.setUpdatedAt(null);
        return toView(defaults);
    }

    private HelpAgentConfigView toView(HelpAgentConfigEntity e) {
        return new HelpAgentConfigView(
                e.getId(),
                e.getOrganizationId(),
                e.isEnabled(),
                e.getAiConfigId(),
                e.isRetrievalEnabled(),
                e.getTopK(),
                e.getSimilarityThreshold(),
                e.getMaxHistoryTurns(),
                e.getMaxQuestionChars(),
                e.isSendUserContext(),
                e.getRetentionDays(),
                e.getPerUserRequestsPerMinute(),
                e.getIndexedCorpusVersion(),
                e.getIndexedAt(),
                localizedIndexError(e.getIndexError()),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
