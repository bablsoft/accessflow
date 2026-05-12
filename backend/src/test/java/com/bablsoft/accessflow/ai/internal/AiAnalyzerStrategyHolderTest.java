package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.MessageSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAnalyzerStrategyHolderTest {

    private static final UUID AI_CONFIG_ID = UUID.randomUUID();
    private static final String SUCCESS_JSON = """
            {"risk_score":10,"risk_level":"LOW","summary":"ok","issues":[],"missing_indexes_detected":false,"affects_row_estimate":null}""";

    @Mock AiConfigRepository aiConfigRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock MessageSource messageSource;
    @Mock ChatModelFactory chatModelFactory;
    @Mock ChatModel chatModel;

    private final SystemPromptRenderer promptRenderer = new SystemPromptRenderer();
    private final AiResponseParser responseParser = new AiResponseParser(JsonMapper.builder().build());

    private AiAnalyzerStrategyHolder holder;

    @BeforeEach
    void setUp() {
        holder = new AiAnalyzerStrategyHolder(aiConfigRepository, encryptionService,
                promptRenderer, responseParser, messageSource, chatModelFactory);
    }

    @Test
    void analyzeThrowsTranslatedExceptionWhenAnthropicHasNoApiKey() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithoutKey(AiProviderType.ANTHROPIC)));
        when(messageSource.getMessage(eq("error.ai.not_configured"), any(), any(Locale.class)))
                .thenReturn("AI is not configured");

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("AI is not configured");
        verifyNoInteractions(chatModelFactory);
    }

    @Test
    void analyzeThrowsWhenAiConfigIdIsNull() {
        when(messageSource.getMessage(eq("error.ai.not_configured"), any(), any(Locale.class)))
                .thenReturn("AI is not configured");

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", null))
                .isInstanceOf(AiAnalysisException.class);
        verifyNoInteractions(chatModelFactory);
        verify(aiConfigRepository, never()).findById(any());
    }

    @Test
    void analyzeBuildsAnthropicDelegateAndCallsIt() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.ANTHROPIC, "ENC(k)", "https://example.com")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk-anthropic");
        when(chatModelFactory.anthropic(eq("sk-anthropic"), eq("test-model"),
                anyInt(), anyInt())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        verify(chatModelFactory).anthropic(eq("sk-anthropic"), eq("test-model"),
                anyInt(), anyInt());
    }

    @Test
    void analyzeBuildsOpenAiDelegateAndCallsIt() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.OPENAI, "ENC(k)", "https://example.com")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk-openai");
        when(chatModelFactory.openAi(eq("sk-openai"), eq("test-model"),
                anyInt(), anyInt())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OPENAI);
        verify(chatModelFactory).openAi(eq("sk-openai"), eq("test-model"),
                anyInt(), anyInt());
    }

    @Test
    void analyzeBuildsOllamaDelegateWithoutApiKey() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.OLLAMA, null, "https://example.com")));
        when(chatModelFactory.ollama(eq("https://example.com"), eq("test-model"), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OLLAMA);
        verifyNoInteractions(encryptionService);
    }

    @Test
    void analyzeIgnoresStoredEndpointForAnthropic() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.ANTHROPIC, "ENC(k)", "https://stored.example.com")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk");
        when(chatModelFactory.anthropic(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        // Factory signature has no baseUrl param for Anthropic — Spring AI's built-in default is used.
        verify(chatModelFactory).anthropic(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void analyzeIgnoresStoredEndpointForOpenAi() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.OPENAI, "ENC(k)", "https://stored.example.com")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk");
        when(chatModelFactory.openAi(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        // Factory signature has no baseUrl param for OpenAI — Spring AI's built-in default is used.
        verify(chatModelFactory).openAi(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void analyzeFallsBackToDefaultOllamaBaseUrl() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.OLLAMA, null, null)));
        when(chatModelFactory.ollama(eq("http://localhost:11434"), anyString(), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        verify(chatModelFactory).ollama(eq("http://localhost:11434"), anyString(), anyInt());
    }

    @Test
    void onConfigUpdatedEvictsCachedDelegate() {
        seedCache(AI_CONFIG_ID, new StubStrategy());

        holder.onConfigUpdated(new AiConfigUpdatedEvent(AI_CONFIG_ID,
                AiProviderType.ANTHROPIC, AiProviderType.OPENAI,
                "claude-sonnet-4-20250514", "gpt-4o", true));

        assertThat(cacheContains(AI_CONFIG_ID)).isFalse();
    }

    @Test
    void onConfigDeletedEvictsCachedDelegate() {
        seedCache(AI_CONFIG_ID, new StubStrategy());

        holder.onConfigDeleted(new AiConfigDeletedEvent(AI_CONFIG_ID));

        assertThat(cacheContains(AI_CONFIG_ID)).isFalse();
    }

    @Test
    void onConfigUpdatedIsSilentForUncachedConfig() {
        holder.onConfigUpdated(new AiConfigUpdatedEvent(AI_CONFIG_ID,
                AiProviderType.ANTHROPIC, AiProviderType.ANTHROPIC,
                "claude-sonnet-4-20250514", "claude-sonnet-4-20250514", false));

        assertThat(cacheContains(AI_CONFIG_ID)).isFalse();
    }

    @Test
    void analyzeReusesCachedDelegateOnSubsequentCalls() {
        var stub = new StubStrategy();
        seedCache(AI_CONFIG_ID, stub);

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);
        holder.analyze("SELECT 2", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(stub.calls).isEqualTo(2);
        verify(aiConfigRepository, never()).findById(any());
        verify(chatModelFactory, times(0)).anthropic(any(), any(), anyInt(), anyInt());
    }

    private AiConfigEntity entityWithoutKey(AiProviderType provider) {
        var entity = new AiConfigEntity();
        entity.setId(AI_CONFIG_ID);
        entity.setOrganizationId(UUID.randomUUID());
        entity.setName("Test");
        entity.setProvider(provider);
        entity.setModel("test-model");
        return entity;
    }

    private AiConfigEntity entityWithKey(AiProviderType provider, String ciphertext, String endpoint) {
        var entity = entityWithoutKey(provider);
        entity.setApiKeyEncrypted(ciphertext);
        entity.setEndpoint(endpoint);
        return entity;
    }

    private ChatResponse successChatResponse() {
        var generation = new Generation(new AssistantMessage(SUCCESS_JSON));
        var metadata = ChatResponseMetadata.builder()
                .model("test-model")
                .usage(new DefaultUsage(10, 5))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @SuppressWarnings("unchecked")
    private void seedCache(UUID aiConfigId, com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy delegate) {
        try {
            var field = AiAnalyzerStrategyHolder.class.getDeclaredField("cache");
            field.setAccessible(true);
            var map = (java.util.concurrent.ConcurrentHashMap<UUID, com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy>)
                    field.get(holder);
            map.put(aiConfigId, delegate);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean cacheContains(UUID aiConfigId) {
        try {
            var field = AiAnalyzerStrategyHolder.class.getDeclaredField("cache");
            field.setAccessible(true);
            var map = (java.util.concurrent.ConcurrentHashMap<UUID, com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy>)
                    field.get(holder);
            return map.containsKey(aiConfigId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static class StubStrategy implements com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy {
        int calls = 0;

        @Override
        public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext, String language,
                                        UUID aiConfigId) {
            calls++;
            return null;
        }
    }
}
