package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiConfigService;
import com.partqam.accessflow.ai.api.AiConfigView;
import com.partqam.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.partqam.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
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

import java.time.Instant;
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

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final String SUCCESS_JSON = """
            {"risk_score":10,"risk_level":"LOW","summary":"ok","issues":[],"missing_indexes_detected":false,"affects_row_estimate":null}""";

    @Mock AiConfigService aiConfigService;
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
        holder = new AiAnalyzerStrategyHolder(aiConfigService, aiConfigRepository, encryptionService,
                promptRenderer, responseParser, messageSource, chatModelFactory);
    }

    @Test
    void analyzeThrowsTranslatedExceptionWhenAnthropicHasNoApiKey() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(view(AiProviderType.ANTHROPIC));
        when(aiConfigRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entityWithoutKey()));
        when(messageSource.getMessage(eq("error.ai.not_configured"), any(), any(Locale.class)))
                .thenReturn("AI is not configured");

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("AI is not configured");
        verifyNoInteractions(chatModelFactory);
    }

    @Test
    void analyzeThrowsTranslatedExceptionWhenViewHasNoPersistedRow() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(transientDefaultView());
        when(messageSource.getMessage(eq("error.ai.not_configured"), any(), any(Locale.class)))
                .thenReturn("AI is not configured");

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisException.class);
        verify(aiConfigRepository, never()).findByOrganizationId(any());
        verifyNoInteractions(chatModelFactory);
    }

    @Test
    void analyzeBuildsAnthropicDelegateAndCallsIt() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(view(AiProviderType.ANTHROPIC));
        when(aiConfigRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entityWithKey("ENC(k)")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk-anthropic");
        when(chatModelFactory.anthropic(eq("sk-anthropic"), eq("https://example.com"), eq("test-model"),
                anyInt(), anyInt())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        verify(chatModelFactory).anthropic(eq("sk-anthropic"), eq("https://example.com"), eq("test-model"),
                anyInt(), anyInt());
    }

    @Test
    void analyzeBuildsOpenAiDelegateAndCallsIt() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(view(AiProviderType.OPENAI));
        when(aiConfigRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entityWithKey("ENC(k)")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk-openai");
        when(chatModelFactory.openAi(eq("sk-openai"), eq("https://example.com"), eq("test-model"),
                anyInt(), anyInt())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OPENAI);
        verify(chatModelFactory).openAi(eq("sk-openai"), eq("https://example.com"), eq("test-model"),
                anyInt(), anyInt());
    }

    @Test
    void analyzeBuildsOllamaDelegateWithoutApiKey() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(view(AiProviderType.OLLAMA));
        when(chatModelFactory.ollama(eq("https://example.com"), eq("test-model"), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OLLAMA);
        verify(aiConfigRepository, never()).findByOrganizationId(any());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void analyzeFallsBackToDefaultBaseUrlWhenEndpointBlank() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(viewWithEndpoint(AiProviderType.ANTHROPIC, ""));
        when(aiConfigRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entityWithKey("ENC(k)")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk");
        when(chatModelFactory.anthropic(anyString(), eq("https://api.anthropic.com"), anyString(),
                anyInt(), anyInt())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        verify(chatModelFactory).anthropic(anyString(), eq("https://api.anthropic.com"), anyString(),
                anyInt(), anyInt());
    }

    @Test
    void analyzeFallsBackToDefaultOpenAiBaseUrl() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(viewWithEndpoint(AiProviderType.OPENAI, null));
        when(aiConfigRepository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entityWithKey("ENC(k)")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk");
        when(chatModelFactory.openAi(anyString(), eq("https://api.openai.com"), anyString(),
                anyInt(), anyInt())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        verify(chatModelFactory).openAi(anyString(), eq("https://api.openai.com"), anyString(),
                anyInt(), anyInt());
    }

    @Test
    void analyzeFallsBackToDefaultOllamaBaseUrl() {
        when(aiConfigService.getOrDefault(ORG_ID)).thenReturn(viewWithEndpoint(AiProviderType.OLLAMA, null));
        when(chatModelFactory.ollama(eq("http://localhost:11434"), anyString(), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        verify(chatModelFactory).ollama(eq("http://localhost:11434"), anyString(), anyInt());
    }

    @Test
    void onConfigUpdatedEvictsCachedDelegate() {
        seedCache(ORG_ID, new StubStrategy());

        holder.onConfigUpdated(new AiConfigUpdatedEvent(ORG_ID,
                AiProviderType.ANTHROPIC, AiProviderType.OPENAI,
                "claude-sonnet-4-20250514", "gpt-4o", true));

        assertThat(cacheContains(ORG_ID)).isFalse();
    }

    @Test
    void onConfigUpdatedIsSilentForUncachedOrg() {
        holder.onConfigUpdated(new AiConfigUpdatedEvent(ORG_ID,
                AiProviderType.ANTHROPIC, AiProviderType.ANTHROPIC,
                "claude-sonnet-4-20250514", "claude-sonnet-4-20250514", false));

        assertThat(cacheContains(ORG_ID)).isFalse();
    }

    @Test
    void analyzeReusesCachedDelegateOnSubsequentCalls() {
        var stub = new StubStrategy();
        seedCache(ORG_ID, stub);

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);
        holder.analyze("SELECT 2", DbType.POSTGRESQL, null, "en", ORG_ID);

        assertThat(stub.calls).isEqualTo(2);
        verify(aiConfigService, never()).getOrDefault(any());
        verify(aiConfigRepository, never()).findByOrganizationId(any());
        verify(chatModelFactory, times(0)).anthropic(any(), any(), any(), anyInt(), anyInt());
    }

    private AiConfigView view(AiProviderType provider) {
        return viewWithEndpoint(provider, "https://example.com");
    }

    private AiConfigView viewWithEndpoint(AiProviderType provider, String endpoint) {
        var now = Instant.now();
        return new AiConfigView(UUID.randomUUID(), ORG_ID, provider,
                "test-model", endpoint, true, 30_000, 8_000, 2_000,
                true, false, true, true, now, now);
    }

    private AiConfigView transientDefaultView() {
        var now = Instant.now();
        return new AiConfigView(null, ORG_ID, AiProviderType.ANTHROPIC,
                "claude-sonnet-4-20250514", "https://api.anthropic.com/v1", false,
                30_000, 8_000, 2_000, true, false, true, true, now, now);
    }

    private AiConfigEntity entityWithoutKey() {
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setProvider(AiProviderType.ANTHROPIC);
        entity.setModel("test-model");
        return entity;
    }

    private AiConfigEntity entityWithKey(String ciphertext) {
        var entity = entityWithoutKey();
        entity.setApiKeyEncrypted(ciphertext);
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
    private void seedCache(UUID orgId, com.partqam.accessflow.ai.api.AiAnalyzerStrategy delegate) {
        try {
            var field = AiAnalyzerStrategyHolder.class.getDeclaredField("cache");
            field.setAccessible(true);
            var map = (java.util.concurrent.ConcurrentHashMap<UUID, com.partqam.accessflow.ai.api.AiAnalyzerStrategy>)
                    field.get(holder);
            map.put(orgId, delegate);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean cacheContains(UUID orgId) {
        try {
            var field = AiAnalyzerStrategyHolder.class.getDeclaredField("cache");
            field.setAccessible(true);
            var map = (java.util.concurrent.ConcurrentHashMap<UUID, com.partqam.accessflow.ai.api.AiAnalyzerStrategy>)
                    field.get(holder);
            return map.containsKey(orgId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static class StubStrategy implements com.partqam.accessflow.ai.api.AiAnalyzerStrategy {
        int calls = 0;

        @Override
        public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext, String language,
                                        UUID organizationId) {
            calls++;
            return null;
        }
    }
}
