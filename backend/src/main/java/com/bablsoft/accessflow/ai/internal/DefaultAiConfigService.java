package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigEndpointRequiredException;
import com.bablsoft.accessflow.ai.api.AiConfigInUseException;
import com.bablsoft.accessflow.ai.api.AiConfigInvalidPromptException;
import com.bablsoft.accessflow.ai.api.AiConfigModelCommand;
import com.bablsoft.accessflow.ai.api.AiConfigModelView;
import com.bablsoft.accessflow.ai.api.AiConfigNameAlreadyExistsException;
import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.AiConfigOrchestrationInvalidException;
import com.bablsoft.accessflow.ai.api.AiConfigRagInvalidException;
import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigModelEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigModelRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.api.VotingStrategy;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
@RequiredArgsConstructor
class DefaultAiConfigService implements AiConfigService {

    static final String DEFAULT_LANGFUSE_PROMPT_LABEL = "production";
    private static final TypeReference<List<String>> PATTERN_LIST = new TypeReference<>() { };

    private final AiConfigRepository repository;
    private final AiConfigModelRepository modelRepository;
    private final CredentialEncryptionService encryptionService;
    private final DatasourceLookupService datasourceLookupService;
    private final ApplicationEventPublisher eventPublisher;
    private final SystemPromptRenderer promptRenderer;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<AiConfigView> list(UUID organizationId) {
        var entities = repository.findAllByOrganizationIdOrderByNameAsc(organizationId);
        if (entities.isEmpty()) {
            return List.of();
        }
        var ids = new HashSet<UUID>(entities.size());
        for (var e : entities) {
            ids.add(e.getId());
        }
        var counts = datasourceLookupService.countsByAiConfigIds(ids);
        return entities.stream()
                .map(e -> toView(e, counts.getOrDefault(e.getId(), 0), List.of(),
                        parseGuardrails(e.getGuardrailPatterns())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AiConfigView get(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        var inUse = datasourceLookupService.countsByAiConfigIds(Set.of(entity.getId()))
                .getOrDefault(entity.getId(), 0);
        return toView(entity, inUse, loadModelViews(entity.getId()),
                parseGuardrails(entity.getGuardrailPatterns()));
    }

    @Override
    @Transactional
    public AiConfigView create(UUID organizationId, CreateAiConfigCommand command) {
        var trimmedName = trim(command.name());
        if (trimmedName == null || trimmedName.isBlank()) {
            throw new IllegalArgumentException("AI config name is required");
        }
        if (repository.existsByOrganizationIdAndNameIgnoreCase(organizationId, trimmedName)) {
            throw new AiConfigNameAlreadyExistsException(trimmedName);
        }
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setName(trimmedName);
        entity.setProvider(command.provider());
        entity.setModel(command.model());
        entity.setEndpoint(blankToNull(command.endpoint()));
        if (command.apiKey() != null && !command.apiKey().isBlank()) {
            entity.setApiKeyEncrypted(encryptionService.encrypt(command.apiKey()));
        }
        if (command.timeoutMs() != null) {
            entity.setTimeoutMs(command.timeoutMs());
        }
        if (command.maxPromptTokens() != null) {
            entity.setMaxPromptTokens(command.maxPromptTokens());
        }
        if (command.maxCompletionTokens() != null) {
            entity.setMaxCompletionTokens(command.maxCompletionTokens());
        }
        entity.setSystemPromptTemplate(blankToNull(command.systemPromptTemplate()));
        entity.setLangfusePromptName(blankToNull(command.langfusePromptName()));
        entity.setLangfusePromptLabel(blankToNull(command.langfusePromptLabel()));
        normalizeLangfusePrompt(entity);
        entity.setRagEnabled(Boolean.TRUE.equals(command.ragEnabled()));
        entity.setRagStoreType(command.ragStoreType());
        if (command.ragTopK() != null) {
            entity.setRagTopK(command.ragTopK());
        }
        if (command.ragSimilarityThreshold() != null) {
            entity.setRagSimilarityThreshold(command.ragSimilarityThreshold());
        }
        entity.setRagEndpoint(blankToNull(command.ragEndpoint()));
        entity.setRagCollection(blankToNull(command.ragCollection()));
        if (command.ragApiKey() != null && !command.ragApiKey().isBlank()) {
            entity.setRagApiKeyEncrypted(encryptionService.encrypt(command.ragApiKey()));
        }
        entity.setEmbeddingProvider(command.embeddingProvider());
        entity.setEmbeddingModel(blankToNull(command.embeddingModel()));
        entity.setEmbeddingEndpoint(blankToNull(command.embeddingEndpoint()));
        if (command.embeddingApiKey() != null && !command.embeddingApiKey().isBlank()) {
            entity.setEmbeddingApiKeyEncrypted(encryptionService.encrypt(command.embeddingApiKey()));
        }
        applyOrchestration(entity, command.orchestrationEnabled(), command.votingStrategy(),
                command.votingWeight(), command.guardrailPatterns());
        var now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        requireEndpointForOpenAiCompatible(entity);
        requireSqlPlaceholder(entity);
        validateRag(entity);
        var saved = repository.save(entity);
        replaceModels(saved.getId(), command.models());
        return toView(saved, 0, loadModelViews(saved.getId()),
                parseGuardrails(saved.getGuardrailPatterns()));
    }

    @Override
    @Transactional
    public AiConfigView update(UUID id, UUID organizationId, UpdateAiConfigCommand command) {
        var entity = loadInOrganization(id, organizationId);
        var oldProvider = entity.getProvider();
        var oldModel = entity.getModel();
        var oldCiphertext = entity.getApiKeyEncrypted();
        var oldPrompt = entity.getSystemPromptTemplate();
        var oldLangfusePromptName = entity.getLangfusePromptName();
        var oldLangfusePromptLabel = entity.getLangfusePromptLabel();
        var oldRagFingerprint = RagFingerprint.of(entity);
        var oldOrchFingerprint = OrchestrationFingerprint.of(entity);
        if (command.name() != null) {
            var trimmedName = trim(command.name());
            if (trimmedName == null || trimmedName.isBlank()) {
                throw new IllegalArgumentException("AI config name must not be blank");
            }
            if (!trimmedName.equalsIgnoreCase(entity.getName())
                    && repository.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(organizationId, trimmedName, id)) {
                throw new AiConfigNameAlreadyExistsException(trimmedName);
            }
            entity.setName(trimmedName);
        }
        if (command.provider() != null) {
            entity.setProvider(command.provider());
        }
        if (command.model() != null) {
            entity.setModel(command.model());
        }
        if (command.endpoint() != null) {
            entity.setEndpoint(blankToNull(command.endpoint()));
        }
        applyApiKey(entity, command.apiKey());
        if (command.timeoutMs() != null) {
            entity.setTimeoutMs(command.timeoutMs());
        }
        if (command.maxPromptTokens() != null) {
            entity.setMaxPromptTokens(command.maxPromptTokens());
        }
        if (command.maxCompletionTokens() != null) {
            entity.setMaxCompletionTokens(command.maxCompletionTokens());
        }
        if (command.systemPromptTemplate() != null) {
            entity.setSystemPromptTemplate(blankToNull(command.systemPromptTemplate()));
        }
        if (command.langfusePromptName() != null) {
            entity.setLangfusePromptName(blankToNull(command.langfusePromptName()));
        }
        if (command.langfusePromptLabel() != null) {
            entity.setLangfusePromptLabel(blankToNull(command.langfusePromptLabel()));
        }
        normalizeLangfusePrompt(entity);
        applyRagUpdate(entity, command);
        applyOrchestrationUpdate(entity, command);
        entity.setUpdatedAt(Instant.now());
        requireEndpointForOpenAiCompatible(entity);
        requireSqlPlaceholder(entity);
        validateRag(entity);
        var saved = repository.save(entity);
        var modelsChanged = command.models() != null;
        if (modelsChanged) {
            replaceModels(saved.getId(), command.models());
        }
        var apiKeyChanged = !Objects.equals(oldCiphertext, saved.getApiKeyEncrypted());
        var langfusePromptChanged = !Objects.equals(oldLangfusePromptName, saved.getLangfusePromptName())
                || !Objects.equals(oldLangfusePromptLabel, saved.getLangfusePromptLabel());
        var promptChanged = !Objects.equals(oldPrompt, saved.getSystemPromptTemplate())
                || langfusePromptChanged;
        var ragChanged = !oldRagFingerprint.equals(RagFingerprint.of(saved));
        var orchestrationChanged = !oldOrchFingerprint.equals(OrchestrationFingerprint.of(saved))
                || modelsChanged;
        if (oldProvider != saved.getProvider()
                || !Objects.equals(oldModel, saved.getModel())
                || apiKeyChanged
                || promptChanged
                || ragChanged
                || orchestrationChanged
                || hasConnectivityChange(command)) {
            eventPublisher.publishEvent(new AiConfigUpdatedEvent(
                    saved.getId(), oldProvider, saved.getProvider(),
                    oldModel, saved.getModel(), apiKeyChanged, promptChanged, ragChanged,
                    orchestrationChanged));
        }
        var inUse = datasourceLookupService.countsByAiConfigIds(Set.of(saved.getId()))
                .getOrDefault(saved.getId(), 0);
        return toView(saved, inUse, loadModelViews(saved.getId()),
                parseGuardrails(saved.getGuardrailPatterns()));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        var refs = datasourceLookupService.findRefsByAiConfigId(entity.getId());
        if (!refs.isEmpty()) {
            var converted = refs.stream()
                    .map(r -> new AiConfigInUseException.DatasourceRef(r.id(), r.name()))
                    .toList();
            throw new AiConfigInUseException(entity.getId(), converted);
        }
        modelRepository.deleteByAiConfigId(entity.getId());
        repository.delete(entity);
        eventPublisher.publishEvent(new AiConfigDeletedEvent(entity.getId()));
    }

    @Override
    public String defaultSystemPromptTemplate() {
        return promptRenderer.defaultTemplate();
    }

    private static void requireEndpointForOpenAiCompatible(AiConfigEntity entity) {
        if (entity.getProvider() == AiProviderType.OPENAI_COMPATIBLE
                && (entity.getEndpoint() == null || entity.getEndpoint().isBlank())) {
            throw new AiConfigEndpointRequiredException();
        }
    }

    /**
     * A Langfuse prompt name always carries a label (defaulting to {@code "production"}); clearing
     * the name clears the label too.
     */
    private static void normalizeLangfusePrompt(AiConfigEntity entity) {
        if (entity.getLangfusePromptName() == null) {
            entity.setLangfusePromptLabel(null);
        } else if (entity.getLangfusePromptLabel() == null || entity.getLangfusePromptLabel().isBlank()) {
            entity.setLangfusePromptLabel(DEFAULT_LANGFUSE_PROMPT_LABEL);
        }
    }

    private static void requireSqlPlaceholder(AiConfigEntity entity) {
        var template = entity.getSystemPromptTemplate();
        if (template != null && !template.contains(SystemPromptRenderer.SQL_PLACEHOLDER)) {
            throw new AiConfigInvalidPromptException();
        }
    }

    private void applyRagUpdate(AiConfigEntity entity, UpdateAiConfigCommand command) {
        if (command.ragEnabled() != null) {
            entity.setRagEnabled(command.ragEnabled());
        }
        if (command.ragStoreType() != null) {
            entity.setRagStoreType(command.ragStoreType());
        }
        if (command.ragTopK() != null) {
            entity.setRagTopK(command.ragTopK());
        }
        if (command.ragSimilarityThreshold() != null) {
            entity.setRagSimilarityThreshold(command.ragSimilarityThreshold());
        }
        if (command.ragEndpoint() != null) {
            entity.setRagEndpoint(blankToNull(command.ragEndpoint()));
        }
        if (command.ragCollection() != null) {
            entity.setRagCollection(blankToNull(command.ragCollection()));
        }
        entity.setRagApiKeyEncrypted(
                resolveEncryptedKey(command.ragApiKey(), entity.getRagApiKeyEncrypted()));
        if (command.embeddingProvider() != null) {
            entity.setEmbeddingProvider(command.embeddingProvider());
        }
        if (command.embeddingModel() != null) {
            entity.setEmbeddingModel(blankToNull(command.embeddingModel()));
        }
        if (command.embeddingEndpoint() != null) {
            entity.setEmbeddingEndpoint(blankToNull(command.embeddingEndpoint()));
        }
        entity.setEmbeddingApiKeyEncrypted(
                resolveEncryptedKey(command.embeddingApiKey(), entity.getEmbeddingApiKeyEncrypted()));
    }

    /** Mirrors {@link UpdateAiConfigCommand} masking semantics for an encrypted key field. */
    private String resolveEncryptedKey(String submitted, String current) {
        if (submitted == null || UpdateAiConfigCommand.MASKED_API_KEY.equals(submitted)) {
            return current;
        }
        if (submitted.isBlank()) {
            return null;
        }
        return encryptionService.encrypt(submitted);
    }

    private static void validateRag(AiConfigEntity e) {
        if (!e.isRagEnabled()) {
            return;
        }
        if (e.getRagStoreType() == null) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.store_type_required");
        }
        if (e.getEmbeddingProvider() == null) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.embedding_provider_required");
        }
        if (e.getEmbeddingProvider() == AiProviderType.ANTHROPIC) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.embedding_provider_invalid");
        }
        if (e.getEmbeddingModel() == null || e.getEmbeddingModel().isBlank()) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.embedding_model_required");
        }
        if (e.getRagStoreType() == RagStoreType.QDRANT) {
            if (e.getRagEndpoint() == null || e.getRagEndpoint().isBlank()) {
                throw new AiConfigRagInvalidException("error.ai_config.rag.external_endpoint_required");
            }
            if (e.getRagCollection() == null || e.getRagCollection().isBlank()) {
                throw new AiConfigRagInvalidException("error.ai_config.rag.external_collection_required");
            }
        }
        if (e.getRagTopK() < 1 || e.getRagTopK() > 20) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.top_k_range");
        }
        if (e.getRagSimilarityThreshold() < 0 || e.getRagSimilarityThreshold() > 1) {
            throw new AiConfigRagInvalidException("error.ai_config.rag.threshold_range");
        }
    }

    /**
     * The RAG / embedding fields that affect the cached analyzer delegate. An update that changes any
     * of them must evict the delegate so the next call rebuilds the retriever / vector store.
     */
    private record RagFingerprint(
            boolean ragEnabled, RagStoreType ragStoreType, int ragTopK, double ragSimilarityThreshold,
            String ragEndpoint, String ragCollection, String ragApiKeyEncrypted,
            AiProviderType embeddingProvider, String embeddingModel, String embeddingEndpoint,
            String embeddingApiKeyEncrypted) {

        static RagFingerprint of(AiConfigEntity e) {
            return new RagFingerprint(e.isRagEnabled(), e.getRagStoreType(), e.getRagTopK(),
                    e.getRagSimilarityThreshold(), e.getRagEndpoint(), e.getRagCollection(),
                    e.getRagApiKeyEncrypted(), e.getEmbeddingProvider(), e.getEmbeddingModel(),
                    e.getEmbeddingEndpoint(), e.getEmbeddingApiKeyEncrypted());
        }
    }

    private boolean hasConnectivityChange(UpdateAiConfigCommand command) {
        return command.endpoint() != null
                || command.timeoutMs() != null
                || command.maxCompletionTokens() != null;
    }

    private void applyApiKey(AiConfigEntity entity, String submitted) {
        if (submitted == null || UpdateAiConfigCommand.MASKED_API_KEY.equals(submitted)) {
            return;
        }
        if (submitted.isBlank()) {
            entity.setApiKeyEncrypted(null);
            return;
        }
        entity.setApiKeyEncrypted(encryptionService.encrypt(submitted));
    }

    private AiConfigEntity loadInOrganization(UUID id, UUID organizationId) {
        return repository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new AiConfigNotFoundException(id));
    }

    private AiConfigView toView(AiConfigEntity entity, int inUseCount,
            List<AiConfigModelView> models, List<String> guardrailPatterns) {
        return new AiConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getName(),
                entity.getProvider(),
                entity.getModel(),
                entity.getEndpoint(),
                entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank(),
                entity.getTimeoutMs(),
                entity.getMaxPromptTokens(),
                entity.getMaxCompletionTokens(),
                entity.getSystemPromptTemplate(),
                entity.getLangfusePromptName(),
                entity.getLangfusePromptLabel(),
                entity.isRagEnabled(),
                entity.getRagStoreType(),
                entity.getRagTopK(),
                entity.getRagSimilarityThreshold(),
                entity.getRagEndpoint(),
                entity.getRagCollection(),
                entity.getRagApiKeyEncrypted() != null && !entity.getRagApiKeyEncrypted().isBlank(),
                entity.getEmbeddingProvider(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingEndpoint(),
                entity.getEmbeddingApiKeyEncrypted() != null && !entity.getEmbeddingApiKeyEncrypted().isBlank(),
                entity.isOrchestrationEnabled(),
                entity.getVotingStrategy(),
                entity.getVotingWeight(),
                guardrailPatterns,
                models,
                inUseCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    // --- Multi-model orchestration + guardrails (AF-450) ---

    private void applyOrchestration(AiConfigEntity entity, Boolean orchestrationEnabled,
            VotingStrategy votingStrategy, Double votingWeight, List<String> guardrailPatterns) {
        entity.setOrchestrationEnabled(Boolean.TRUE.equals(orchestrationEnabled));
        if (votingStrategy != null) {
            entity.setVotingStrategy(votingStrategy);
        }
        if (votingWeight != null) {
            requirePositiveWeight(votingWeight);
            entity.setVotingWeight(votingWeight);
        }
        entity.setGuardrailPatterns(serializeGuardrails(guardrailPatterns));
    }

    private void applyOrchestrationUpdate(AiConfigEntity entity, UpdateAiConfigCommand command) {
        if (command.orchestrationEnabled() != null) {
            entity.setOrchestrationEnabled(command.orchestrationEnabled());
        }
        if (command.votingStrategy() != null) {
            entity.setVotingStrategy(command.votingStrategy());
        }
        if (command.votingWeight() != null) {
            requirePositiveWeight(command.votingWeight());
            entity.setVotingWeight(command.votingWeight());
        }
        if (command.guardrailPatterns() != null) {
            entity.setGuardrailPatterns(serializeGuardrails(command.guardrailPatterns()));
        }
    }

    /**
     * Replaces the {@code ai_config_model} members for a config (AF-450). Members carrying a known id
     * are updated in place (preserving a masked API key); members without a matching id are inserted;
     * existing members absent from {@code models} are deleted. A {@code null} list leaves members
     * untouched (the update did not address orchestration members).
     */
    private void replaceModels(UUID configId, List<AiConfigModelCommand> models) {
        if (models == null) {
            return;
        }
        var existing = modelRepository.findByAiConfigIdOrderBySortOrderAsc(configId);
        var existingById = new java.util.HashMap<UUID, AiConfigModelEntity>();
        for (var e : existing) {
            existingById.put(e.getId(), e);
        }
        var keptIds = new HashSet<UUID>();
        int order = 0;
        for (var m : models) {
            validateMember(m);
            AiConfigModelEntity row;
            if (m.id() != null && existingById.containsKey(m.id())) {
                row = existingById.get(m.id());
                keptIds.add(row.getId());
            } else {
                row = new AiConfigModelEntity();
                row.setId(UUID.randomUUID());
                row.setAiConfigId(configId);
                row.setCreatedAt(Instant.now());
            }
            row.setProvider(m.provider());
            row.setModel(m.model().trim());
            row.setEndpoint(blankToNull(m.endpoint()));
            row.setApiKeyEncrypted(resolveEncryptedKey(m.apiKey(), row.getApiKeyEncrypted()));
            row.setWeight(m.weight() == null ? 1.0 : m.weight());
            row.setEnabled(m.enabled() == null || m.enabled());
            row.setSortOrder(order++);
            modelRepository.save(row);
        }
        for (var e : existing) {
            if (!keptIds.contains(e.getId())) {
                modelRepository.delete(e);
            }
        }
    }

    private static void validateMember(AiConfigModelCommand m) {
        if (m.provider() == null) {
            throw new AiConfigOrchestrationInvalidException(
                    "error.ai_config.orchestration_member_provider_required");
        }
        if (m.model() == null || m.model().isBlank()) {
            throw new AiConfigOrchestrationInvalidException(
                    "error.ai_config.orchestration_member_model_required");
        }
        if (m.weight() != null && m.weight() <= 0) {
            throw new AiConfigOrchestrationInvalidException("error.ai_config.voting_weight_invalid");
        }
        if (m.provider() == AiProviderType.OPENAI_COMPATIBLE
                && (m.endpoint() == null || m.endpoint().isBlank())) {
            throw new AiConfigOrchestrationInvalidException(
                    "error.ai_config.orchestration_member_endpoint_required");
        }
    }

    private static void requirePositiveWeight(double weight) {
        if (weight <= 0) {
            throw new AiConfigOrchestrationInvalidException("error.ai_config.voting_weight_invalid");
        }
    }

    private String serializeGuardrails(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return "[]";
        }
        var cleaned = new ArrayList<String>(patterns.size());
        for (var p : patterns) {
            if (p == null || p.isBlank()) {
                continue;
            }
            var trimmed = p.trim();
            try {
                Pattern.compile(trimmed);
            } catch (PatternSyntaxException e) {
                throw new AiConfigOrchestrationInvalidException(
                        "error.ai_config.guardrail_pattern_invalid");
            }
            cleaned.add(trimmed);
        }
        return objectMapper.writeValueAsString(cleaned);
    }

    private List<String> parseGuardrails(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, PATTERN_LIST);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<AiConfigModelView> loadModelViews(UUID configId) {
        return modelRepository.findByAiConfigIdOrderBySortOrderAsc(configId).stream()
                .map(m -> new AiConfigModelView(m.getId(), m.getProvider(), m.getModel(),
                        m.getEndpoint(),
                        m.getApiKeyEncrypted() != null && !m.getApiKeyEncrypted().isBlank(),
                        m.getWeight(), m.isEnabled()))
                .toList();
    }

    private record OrchestrationFingerprint(boolean enabled, VotingStrategy strategy, double weight,
            String guardrailPatterns) {

        static OrchestrationFingerprint of(AiConfigEntity e) {
            return new OrchestrationFingerprint(e.isOrchestrationEnabled(), e.getVotingStrategy(),
                    e.getVotingWeight(), e.getGuardrailPatterns());
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        return value.isBlank() ? null : value;
    }
}
