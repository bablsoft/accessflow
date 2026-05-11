package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.ai.api.AiConfigNotFoundException;
import com.partqam.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.partqam.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.Locale;
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
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private final AiConfigRepository aiConfigRepository;
    private final CredentialEncryptionService encryptionService;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;
    private final MessageSource messageSource;
    private final ChatModelFactory chatModelFactory;

    private final ConcurrentHashMap<UUID, AiAnalyzerStrategy> cache = new ConcurrentHashMap<>();

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

    @ApplicationModuleListener
    void onConfigUpdated(AiConfigUpdatedEvent event) {
        var removed = cache.remove(event.aiConfigId());
        if (removed != null) {
            log.info("Evicted AI analyzer delegate for ai_config={} (provider {} -> {}, model {} -> {}, api_key_changed={})",
                    event.aiConfigId(), event.oldProvider(), event.newProvider(),
                    event.oldModel(), event.newModel(), event.apiKeyChanged());
        }
    }

    @ApplicationModuleListener
    void onConfigDeleted(AiConfigDeletedEvent event) {
        var removed = cache.remove(event.aiConfigId());
        if (removed != null) {
            log.info("Evicted AI analyzer delegate for deleted ai_config={}", event.aiConfigId());
        }
    }

    private AiAnalyzerStrategy buildDelegate(AiConfigEntity entity) {
        return switch (entity.getProvider()) {
            case ANTHROPIC -> new AnthropicAnalyzerStrategy(buildAnthropicChatModel(entity), promptRenderer, responseParser);
            case OPENAI -> new OpenAiAnalyzerStrategy(buildOpenAiChatModel(entity), promptRenderer, responseParser);
            case OLLAMA -> new OllamaAnalyzerStrategy(buildOllamaChatModel(entity), promptRenderer, responseParser);
        };
    }

    private ChatModel buildAnthropicChatModel(AiConfigEntity entity) {
        var apiKey = requireApiKey(entity);
        var baseUrl = baseUrlOrDefault(entity, DEFAULT_ANTHROPIC_BASE_URL);
        return chatModelFactory.anthropic(apiKey, baseUrl, entity.getModel(),
                entity.getMaxCompletionTokens(), entity.getTimeoutMs());
    }

    private ChatModel buildOpenAiChatModel(AiConfigEntity entity) {
        var apiKey = requireApiKey(entity);
        var baseUrl = baseUrlOrDefault(entity, DEFAULT_OPENAI_BASE_URL);
        return chatModelFactory.openAi(apiKey, baseUrl, entity.getModel(),
                entity.getMaxCompletionTokens(), entity.getTimeoutMs());
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
