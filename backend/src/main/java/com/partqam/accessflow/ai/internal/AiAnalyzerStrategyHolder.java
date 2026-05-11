package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.ai.api.AiConfigService;
import com.partqam.accessflow.ai.api.AiConfigView;
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
 * Single autowired {@link AiAnalyzerStrategy} bean. Resolves the per-org {@code ai_config} row,
 * builds a provider-specific delegate (Anthropic / OpenAI / Ollama) the first time an org calls
 * {@code analyze(...)}, and caches it. {@link AiConfigUpdatedEvent} evicts the cached delegate for
 * the org after the {@code DefaultAiConfigService.update(...)} transaction commits, so the next
 * call rebuilds against the new row — no application restart needed.
 */
@Service
@RequiredArgsConstructor
class AiAnalyzerStrategyHolder implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(AiAnalyzerStrategyHolder.class);
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private final AiConfigService aiConfigService;
    private final AiConfigRepository aiConfigRepository;
    private final CredentialEncryptionService encryptionService;
    private final SystemPromptRenderer promptRenderer;
    private final AiResponseParser responseParser;
    private final MessageSource messageSource;
    private final ChatModelFactory chatModelFactory;

    private final ConcurrentHashMap<UUID, AiAnalyzerStrategy> cache = new ConcurrentHashMap<>();

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext, String language,
                                    UUID organizationId) {
        var delegate = cache.computeIfAbsent(organizationId, key -> {
            var view = aiConfigService.getOrDefault(key);
            log.debug("Building AI analyzer delegate for org={} provider={} model={}",
                    key, view.provider(), view.model());
            return buildDelegate(view);
        });
        return delegate.analyze(sql, dbType, schemaContext, language, organizationId);
    }

    @ApplicationModuleListener
    void onConfigUpdated(AiConfigUpdatedEvent event) {
        var removed = cache.remove(event.organizationId());
        if (removed != null) {
            log.info("Evicted AI analyzer delegate for org={} (provider {} -> {}, model {} -> {}, api_key_changed={})",
                    event.organizationId(), event.oldProvider(), event.newProvider(),
                    event.oldModel(), event.newModel(), event.apiKeyChanged());
        }
    }

    private AiAnalyzerStrategy buildDelegate(AiConfigView view) {
        return switch (view.provider()) {
            case ANTHROPIC -> new AnthropicAnalyzerStrategy(buildAnthropicChatModel(view), promptRenderer, responseParser);
            case OPENAI -> new OpenAiAnalyzerStrategy(buildOpenAiChatModel(view), promptRenderer, responseParser);
            case OLLAMA -> new OllamaAnalyzerStrategy(buildOllamaChatModel(view), promptRenderer, responseParser);
        };
    }

    private ChatModel buildAnthropicChatModel(AiConfigView view) {
        var apiKey = requireApiKey(view);
        var baseUrl = baseUrlOrDefault(view, DEFAULT_ANTHROPIC_BASE_URL);
        return chatModelFactory.anthropic(apiKey, baseUrl, view.model(),
                view.maxCompletionTokens(), view.timeoutMs());
    }

    private ChatModel buildOpenAiChatModel(AiConfigView view) {
        var apiKey = requireApiKey(view);
        var baseUrl = baseUrlOrDefault(view, DEFAULT_OPENAI_BASE_URL);
        return chatModelFactory.openAi(apiKey, baseUrl, view.model(),
                view.maxCompletionTokens(), view.timeoutMs());
    }

    private ChatModel buildOllamaChatModel(AiConfigView view) {
        var baseUrl = baseUrlOrDefault(view, DEFAULT_OLLAMA_BASE_URL);
        return chatModelFactory.ollama(baseUrl, view.model(), view.maxCompletionTokens());
    }

    private String baseUrlOrDefault(AiConfigView view, String fallback) {
        return (view.endpoint() == null || view.endpoint().isBlank()) ? fallback : view.endpoint();
    }

    private String requireApiKey(AiConfigView view) {
        if (view.id() == null) {
            throw notConfigured();
        }
        var ciphertext = aiConfigRepository.findByOrganizationId(view.organizationId())
                .map(e -> e.getApiKeyEncrypted())
                .orElse(null);
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
