package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single autowired {@link AiAnalyzerStrategy} bean. Resolves the per-row {@code ai_config} entity,
 * builds a provider-specific delegate (Anthropic / OpenAI / Ollama) the first time a config is
 * referenced, and caches it. {@link AiConfigUpdatedEvent} / {@link AiConfigDeletedEvent} evict
 * cached delegates after the originating transaction commits, so the next call rebuilds against
 * the new state — no application restart needed.
 */
@Service
@RequiredArgsConstructor
class AiAnalyzerStrategyHolder implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(AiAnalyzerStrategyHolder.class);
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    // Hugging Face Inference Providers router (OpenAI-compatible). Used by default for the
    // HUGGING_FACE provider; admins override it with a local TGI / Dedicated Endpoint base URL.
    private static final String DEFAULT_HUGGING_FACE_BASE_URL = "https://router.huggingface.co/v1";
    // OPENAI_COMPATIBLE and HUGGING_FACE allow a keyless config (self-hosted vLLM / LM Studio /
    // local TGI etc.). The OpenAI Java client still wants a non-blank key to construct, so
    // substitute a non-secret placeholder.
    private static final String PLACEHOLDER_API_KEY = "not-needed";

    private final AiConfigRepository aiConfigRepository;
    private final CredentialEncryptionService encryptionService;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;
    private final SqlGenerationResponseParser sqlGenerationResponseParser;
    private final MessageSource messageSource;
    private final ChatModelFactory chatModelFactory;
    private final RagComponentsFactory ragComponentsFactory;
    private final LangfusePromptProvider langfusePromptProvider;
    private final LangfuseTracer langfuseTracer;
    private final Clock clock;

    private final ConcurrentHashMap<UUID, AiAnalyzerStrategy> cache = new ConcurrentHashMap<>();
    // Separate cache of bare ChatModels keyed by ai_config id, for the freeform anomaly-summary path
    // (which bypasses the JSON-schema-bound analyze() delegates). Evicted alongside the delegate cache.
    private final ConcurrentHashMap<UUID, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    private static final String ANOMALY_SUMMARY_PREAMBLE = """
            You are a database security analyst. In 2-3 plain sentences, explain why the described \
            database-access pattern looks anomalous relative to the user's historical baseline, and \
            what a reviewer should check. Do not use markdown, headings, or bullet points.""";

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext, String language,
                                    UUID aiConfigId) {
        if (aiConfigId == null) {
            throw notConfigured();
        }
        var delegate = cache.computeIfAbsent(aiConfigId, key -> {
            var entity = aiConfigRepository.findById(key)
                    .orElseThrow(() -> new AiConfigNotFoundException(key));
            log.debug("Building AI analyzer delegate for ai_config={} provider={} model={}",
                    key, entity.getProvider(), entity.getModel());
            return buildDelegate(entity);
        });
        return delegate.analyze(sql, dbType, schemaContext, language, aiConfigId);
    }

    @Override
    public GeneratedSqlResult generateSql(String prompt, DbType dbType, String schemaContext,
                                          String language, UUID aiConfigId) {
        if (aiConfigId == null) {
            throw notConfigured();
        }
        var delegate = cache.computeIfAbsent(aiConfigId, key -> {
            var entity = aiConfigRepository.findById(key)
                    .orElseThrow(() -> new AiConfigNotFoundException(key));
            log.debug("Building AI analyzer delegate for ai_config={} provider={} model={}",
                    key, entity.getProvider(), entity.getModel());
            return buildDelegate(entity);
        });
        return delegate.generateSql(prompt, dbType, schemaContext, language, aiConfigId);
    }

    /**
     * Make a single freeform natural-language call against the organization's first usable
     * {@code ai_config}, reusing the per-config provider resolution. Used by the UBA anomaly
     * summarizer (AF-383). Fail-safe by contract: returns {@link Optional#empty()} when no usable
     * config exists or any provider/parse error occurs — it never throws and never blocks detection.
     */
    Optional<String> summarizeFreeform(UUID organizationId, String userPrompt) {
        if (organizationId == null || userPrompt == null || userPrompt.isBlank()) {
            return Optional.empty();
        }
        try {
            var entity = resolveUsableConfig(organizationId).orElse(null);
            if (entity == null) {
                log.debug("No usable ai_config for org {}; skipping anomaly summary", organizationId);
                return Optional.empty();
            }
            var chatModel = chatModelCache.computeIfAbsent(entity.getId(), key -> buildChatModel(entity));
            var invocation = ChatModelInvoker.invoke(chatModel, ANOMALY_SUMMARY_PREAMBLE, userPrompt,
                    entity.getProvider().name());
            var text = invocation.text();
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.strip());
        } catch (RuntimeException ex) {
            log.warn("Anomaly AI summary failed for org {}: {}", organizationId, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<AiConfigEntity> resolveUsableConfig(UUID organizationId) {
        return aiConfigRepository.findAllByOrganizationIdOrderByNameAsc(organizationId).stream()
                .filter(AiAnalyzerStrategyHolder::isUsable)
                .findFirst();
    }

    private static boolean isUsable(AiConfigEntity entity) {
        var provider = entity.getProvider();
        if (provider == AiProviderType.OLLAMA
                || provider == AiProviderType.OPENAI_COMPATIBLE
                || provider == AiProviderType.HUGGING_FACE) {
            return true;
        }
        return entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank();
    }

    private ChatModel buildChatModel(AiConfigEntity entity) {
        return switch (entity.getProvider()) {
            case ANTHROPIC -> buildAnthropicChatModel(entity);
            case OPENAI -> buildOpenAiChatModel(entity);
            case OPENAI_COMPATIBLE -> buildOpenAiCompatibleChatModel(entity);
            case HUGGING_FACE -> buildHuggingFaceChatModel(entity);
            case OLLAMA -> buildOllamaChatModel(entity);
        };
    }

    @ApplicationModuleListener
    void onConfigUpdated(AiConfigUpdatedEvent event) {
        chatModelCache.remove(event.aiConfigId());
        var removed = cache.remove(event.aiConfigId());
        if (removed != null) {
            log.info("Evicted AI analyzer delegate for ai_config={} (provider {} -> {}, model {} -> {}, api_key_changed={}, prompt_changed={}, rag_changed={})",
                    event.aiConfigId(), event.oldProvider(), event.newProvider(),
                    event.oldModel(), event.newModel(), event.apiKeyChanged(), event.promptChanged(),
                    event.ragChanged());
        }
    }

    @ApplicationModuleListener
    void onConfigDeleted(AiConfigDeletedEvent event) {
        chatModelCache.remove(event.aiConfigId());
        var removed = cache.remove(event.aiConfigId());
        if (removed != null) {
            log.info("Evicted AI analyzer delegate for deleted ai_config={}", event.aiConfigId());
        }
    }

    private AiAnalyzerStrategy buildDelegate(AiConfigEntity entity) {
        var promptSource = buildPromptSource(entity);
        var rag = ragComponentsFactory.retriever(entity);
        var base = switch (entity.getProvider()) {
            case ANTHROPIC -> new AnthropicAnalyzerStrategy(buildAnthropicChatModel(entity), promptRenderer, responseParser, promptSource, sqlGenerationResponseParser, rag);
            case OPENAI -> new OpenAiAnalyzerStrategy(AiProviderType.OPENAI, buildOpenAiChatModel(entity), promptRenderer, responseParser, promptSource, sqlGenerationResponseParser, rag);
            case OPENAI_COMPATIBLE -> new OpenAiAnalyzerStrategy(AiProviderType.OPENAI_COMPATIBLE, buildOpenAiCompatibleChatModel(entity), promptRenderer, responseParser, promptSource, sqlGenerationResponseParser, rag);
            case HUGGING_FACE -> new OpenAiAnalyzerStrategy(AiProviderType.HUGGING_FACE, buildHuggingFaceChatModel(entity), promptRenderer, responseParser, promptSource, sqlGenerationResponseParser, rag);
            case OLLAMA -> new OllamaAnalyzerStrategy(buildOllamaChatModel(entity), promptRenderer, responseParser, promptSource, sqlGenerationResponseParser, rag);
        };
        return new TracingAiAnalyzerStrategy(base, langfuseTracer, entity.getOrganizationId(),
                entity.getProvider(), entity.getModel(), clock);
    }

    /**
     * The effective system-prompt template, resolved per call. When the config names a Langfuse
     * prompt the managed prompt is fetched (and cached) per call, falling back to the locally stored
     * {@code system_prompt_template} when Langfuse / prompt-management is off or the fetch fails.
     */
    private SystemPromptSource buildPromptSource(AiConfigEntity entity) {
        var localTemplate = entity.getSystemPromptTemplate();
        var promptName = entity.getLangfusePromptName();
        if (promptName == null || promptName.isBlank()) {
            return () -> localTemplate;
        }
        var organizationId = entity.getOrganizationId();
        var promptLabel = entity.getLangfusePromptLabel();
        return () -> langfusePromptProvider.resolve(organizationId, promptName, promptLabel)
                .orElse(localTemplate);
    }

    private ChatModel buildAnthropicChatModel(AiConfigEntity entity) {
        var apiKey = requireApiKey(entity);
        return chatModelFactory.anthropic(apiKey, entity.getModel(),
                entity.getMaxCompletionTokens(), entity.getTimeoutMs());
    }

    private ChatModel buildOpenAiChatModel(AiConfigEntity entity) {
        var apiKey = requireApiKey(entity);
        return chatModelFactory.openAi(apiKey, entity.getModel(),
                entity.getMaxCompletionTokens(), entity.getTimeoutMs(), null);
    }

    private ChatModel buildOpenAiCompatibleChatModel(AiConfigEntity entity) {
        var apiKey = optionalApiKey(entity);
        return chatModelFactory.openAi(apiKey, entity.getModel(),
                entity.getMaxCompletionTokens(), entity.getTimeoutMs(), entity.getEndpoint());
    }

    private ChatModel buildHuggingFaceChatModel(AiConfigEntity entity) {
        // Keyless-capable: a HF token is used for the hosted router but local TGI runs tokenless.
        // The endpoint defaults to the HF router; a custom base URL targets local TGI / Dedicated
        // Endpoints. Same OpenAI-compatible client as OPENAI / OPENAI_COMPATIBLE.
        var apiKey = optionalApiKey(entity);
        var baseUrl = baseUrlOrDefault(entity, DEFAULT_HUGGING_FACE_BASE_URL);
        return chatModelFactory.openAi(apiKey, entity.getModel(),
                entity.getMaxCompletionTokens(), entity.getTimeoutMs(), baseUrl);
    }

    private ChatModel buildOllamaChatModel(AiConfigEntity entity) {
        var baseUrl = baseUrlOrDefault(entity, DEFAULT_OLLAMA_BASE_URL);
        return chatModelFactory.ollama(baseUrl, entity.getModel(), entity.getMaxCompletionTokens());
    }

    private String baseUrlOrDefault(AiConfigEntity entity, String fallback) {
        var endpoint = entity.getEndpoint();
        return (endpoint == null || endpoint.isBlank()) ? fallback : endpoint;
    }

    private String requireApiKey(AiConfigEntity entity) {
        var ciphertext = entity.getApiKeyEncrypted();
        if (ciphertext == null || ciphertext.isBlank()) {
            throw notConfigured();
        }
        return encryptionService.decrypt(ciphertext);
    }

    private String optionalApiKey(AiConfigEntity entity) {
        var ciphertext = entity.getApiKeyEncrypted();
        if (ciphertext == null || ciphertext.isBlank()) {
            return PLACEHOLDER_API_KEY;
        }
        return encryptionService.decrypt(ciphertext);
    }

    private AiAnalysisException notConfigured() {
        var locale = currentLocale();
        var msg = messageSource.getMessage("error.ai.not_configured", null, locale);
        return new AiAnalysisException(msg);
    }

    private Locale currentLocale() {
        var locale = LocaleContextHolder.getLocale();
        return locale != null ? locale : Locale.ENGLISH;
    }
}
