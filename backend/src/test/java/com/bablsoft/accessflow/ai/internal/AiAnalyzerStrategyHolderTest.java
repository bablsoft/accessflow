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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
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
    @Mock com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigModelRepository aiConfigModelRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock MessageSource messageSource;
    @Mock ChatModelFactory chatModelFactory;
    @Mock RagComponentsFactory ragComponentsFactory;
    @Mock ChatModel chatModel;
    @Mock LangfusePromptProvider langfusePromptProvider;
    @Mock LangfuseTracer langfuseTracer;

    private final SystemPromptRenderer promptRenderer = new SystemPromptRenderer();
    private final AiResponseParser responseParser = new AiResponseParser(JsonMapper.builder().build());
    private final SqlGenerationResponseParser sqlGenerationResponseParser =
            new SqlGenerationResponseParser(JsonMapper.builder().build());
    private final tools.jackson.databind.ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private AiAnalyzerStrategyHolder holder;

    @BeforeEach
    void setUp() {
        holder = new AiAnalyzerStrategyHolder(aiConfigRepository, aiConfigModelRepository, encryptionService,
                promptRenderer, responseParser, sqlGenerationResponseParser, messageSource, chatModelFactory,
                ragComponentsFactory, langfusePromptProvider, langfuseTracer, objectMapper, clock);
        lenient().when(ragComponentsFactory.retriever(any())).thenReturn(RagRetriever.DISABLED);
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
                anyInt(), anyInt(), isNull())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OPENAI);
        verify(chatModelFactory).openAi(eq("sk-openai"), eq("test-model"),
                anyInt(), anyInt(), isNull());
    }

    @Test
    void analyzeBuildsOpenAiCompatibleDelegateWithCustomEndpointAndNoKey() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.OPENAI_COMPATIBLE, null, "https://api.example.com/v1")));
        when(chatModelFactory.openAi(eq("not-needed"), eq("test-model"),
                anyInt(), anyInt(), eq("https://api.example.com/v1"))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OPENAI_COMPATIBLE);
        verify(chatModelFactory).openAi(eq("not-needed"), eq("test-model"),
                anyInt(), anyInt(), eq("https://api.example.com/v1"));
        verifyNoInteractions(encryptionService);
    }

    @Test
    void analyzeBuildsOpenAiCompatibleDelegateWithApiKey() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.OPENAI_COMPATIBLE, "ENC(k)", "https://api.example.com/v1")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk-compat");
        when(chatModelFactory.openAi(eq("sk-compat"), eq("test-model"),
                anyInt(), anyInt(), eq("https://api.example.com/v1"))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        verify(chatModelFactory).openAi(eq("sk-compat"), eq("test-model"),
                anyInt(), anyInt(), eq("https://api.example.com/v1"));
    }

    @Test
    void analyzeBuildsHuggingFaceDelegateWithDefaultRouterUrlAndNoKey() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.HUGGING_FACE, null, null)));
        when(chatModelFactory.openAi(eq("not-needed"), eq("test-model"),
                anyInt(), anyInt(), eq("https://router.huggingface.co/v1"))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        // Keyless local-TGI path: no API key stored -> placeholder; blank endpoint -> HF router default.
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.HUGGING_FACE);
        verify(chatModelFactory).openAi(eq("not-needed"), eq("test-model"),
                anyInt(), anyInt(), eq("https://router.huggingface.co/v1"));
        verifyNoInteractions(encryptionService);
    }

    @Test
    void analyzeBuildsHuggingFaceDelegateWithCustomEndpointAndApiKey() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.HUGGING_FACE, "ENC(k)", "http://localhost:3000/v1")));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("hf_token");
        when(chatModelFactory.openAi(eq("hf_token"), eq("test-model"),
                anyInt(), anyInt(), eq("http://localhost:3000/v1"))).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        // Custom base URL targets a local TGI / dedicated endpoint; an HF token is honored when set.
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.HUGGING_FACE);
        verify(chatModelFactory).openAi(eq("hf_token"), eq("test-model"),
                anyInt(), anyInt(), eq("http://localhost:3000/v1"));
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
        when(chatModelFactory.openAi(anyString(), anyString(), anyInt(), anyInt(), isNull()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        // The OPENAI provider passes a null baseUrl — Spring AI's built-in default endpoint is used,
        // even though the row stores one. (OPENAI_COMPATIBLE is the provider that honors it.)
        verify(chatModelFactory).openAi(anyString(), anyString(), anyInt(), anyInt(), isNull());
    }

    @Test
    void analyzeThreadsCustomSystemPromptIntoDelegate() {
        var entity = entityWithKey(AiProviderType.OPENAI, "ENC(k)", null);
        entity.setSystemPromptTemplate("HOUSE-RULE-MARKER analyze {{sql}} in {{db_type}}");
        when(aiConfigRepository.findById(AI_CONFIG_ID)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk");
        when(chatModelFactory.openAi(anyString(), anyString(), anyInt(), anyInt(), isNull()))
                .thenReturn(chatModel);
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(successChatResponse());

        holder.analyze("SELECT secret FROM t", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        var userText = promptCaptor.getValue().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(userText).contains("HOUSE-RULE-MARKER");
        assertThat(userText).contains("SELECT secret FROM t");
        assertThat(userText).contains("POSTGRESQL");
        // The built-in default template's text must NOT appear — the custom template replaced it.
        assertThat(userText).doesNotContain("database security and performance expert");
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
                "claude-sonnet-4-20250514", "gpt-4o", true, false, false, false));

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
                "claude-sonnet-4-20250514", "claude-sonnet-4-20250514", false, false, false, false));

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

    @Test
    void generateSqlReusesCachedDelegate() {
        var stub = new StubStrategy();
        seedCache(AI_CONFIG_ID, stub);

        var result = holder.generateSql("orders", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.sql()).isEqualTo("SELECT 1");
        verify(aiConfigRepository, never()).findById(any());
    }

    @Test
    void generateSqlThrowsWhenAiConfigIdIsNull() {
        when(messageSource.getMessage(eq("error.ai.not_configured"), any(), any(Locale.class)))
                .thenReturn("AI is not configured");

        assertThatThrownBy(() -> holder.generateSql("orders", DbType.POSTGRESQL, null, "en", null))
                .isInstanceOf(AiAnalysisException.class);
        verifyNoInteractions(chatModelFactory);
        verify(aiConfigRepository, never()).findById(any());
    }

    @Test
    void generateSqlBuildsDelegateAndCallsIt() {
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(entityWithKey(AiProviderType.ANTHROPIC, "ENC(k)", null)));
        when(encryptionService.decrypt("ENC(k)")).thenReturn("sk-anthropic");
        when(chatModelFactory.anthropic(eq("sk-anthropic"), eq("test-model"), anyInt(), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(generatedSqlChatResponse());

        var result = holder.generateSql("all orders", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.sql()).isEqualTo("SELECT 1");
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
    }

    // --- Provider fallback pool (AF-458) ---

    @Test
    void analyzeFailsOverToFallbackConfigsInPriorityOrder() {
        var orgId = UUID.randomUUID();
        var fallback1Id = UUID.randomUUID();
        var fallback2Id = UUID.randomUUID();
        var primary = new ThrowingStrategy(new AiAnalysisException("primary down"));
        var firstFallback = new ThrowingStrategy(new AiAnalysisException("fallback1 down"));
        var secondFallback = new SucceedingStrategy();
        seedCache(AI_CONFIG_ID, primary);
        seedCache(fallback1Id, firstFallback);
        seedCache(fallback2Id, secondFallback);
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(fallbackEntity(AI_CONFIG_ID, orgId, "Primary", null)));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of(
                        fallbackEntity(fallback1Id, orgId, "First", 0),
                        fallbackEntity(fallback2Id, orgId, "Second", 1)));

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OLLAMA);
        assertThat(primary.calls).isEqualTo(1);
        assertThat(firstFallback.calls).isEqualTo(1);
        assertThat(secondFallback.calls).isEqualTo(1);
    }

    @Test
    void analyzeFailoverSkipsTheFailedConfigItself() {
        var orgId = UUID.randomUUID();
        var otherId = UUID.randomUUID();
        var primary = new ThrowingStrategy(new AiAnalysisException("down"));
        var other = new SucceedingStrategy();
        seedCache(AI_CONFIG_ID, primary);
        seedCache(otherId, other);
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(fallbackEntity(AI_CONFIG_ID, orgId, "Primary", 0)));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of(
                        fallbackEntity(AI_CONFIG_ID, orgId, "Primary", 0),
                        fallbackEntity(otherId, orgId, "Other", 1)));

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        // The primary is itself in the fallback pool — it must not be retried after its own failure.
        assertThat(primary.calls).isEqualTo(1);
        assertThat(other.calls).isEqualTo(1);
    }

    @Test
    void analyzeRethrowsOriginalFailureWhenAllFallbacksFail() {
        var orgId = UUID.randomUUID();
        var fallbackId = UUID.randomUUID();
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(new AiAnalysisException("original failure")));
        seedCache(fallbackId, new ThrowingStrategy(new AiAnalysisException("fallback failure")));
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(fallbackEntity(AI_CONFIG_ID, orgId, "Primary", null)));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of(fallbackEntity(fallbackId, orgId, "Fallback", 0)));

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessage("original failure");
    }

    @Test
    void analyzeRethrowsOriginalFailureWhenNoFallbacksConfigured() {
        var orgId = UUID.randomUUID();
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(new AiAnalysisException("no pool")));
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(fallbackEntity(AI_CONFIG_ID, orgId, "Primary", null)));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessage("no pool");
    }

    @Test
    void analyzeParseFailureAlsoTriggersFailover() {
        var orgId = UUID.randomUUID();
        var fallbackId = UUID.randomUUID();
        var fallback = new SucceedingStrategy();
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(
                new com.bablsoft.accessflow.ai.api.AiAnalysisParseException("bad json")));
        seedCache(fallbackId, fallback);
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(fallbackEntity(AI_CONFIG_ID, orgId, "Primary", null)));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of(fallbackEntity(fallbackId, orgId, "Fallback", 0)));

        holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(fallback.calls).isEqualTo(1);
    }

    @Test
    void analyzeDoesNotFailOverOnGuardrailViolation() {
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(
                new com.bablsoft.accessflow.ai.api.AiGuardrailViolationException("blocked", "p.*n")));

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(com.bablsoft.accessflow.ai.api.AiGuardrailViolationException.class);
        verify(aiConfigRepository, never()).findById(any());
        verify(aiConfigRepository, never())
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(any());
    }

    @Test
    void analyzeDoesNotFailOverOnBudgetOrRateLimitExceedance() {
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(
                new com.bablsoft.accessflow.ai.api.AiBudgetExceededException(100, 200)));
        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(com.bablsoft.accessflow.ai.api.AiBudgetExceededException.class);

        seedCache(AI_CONFIG_ID, new ThrowingStrategy(
                new com.bablsoft.accessflow.ai.api.AiRateLimitExceededException(30, 10)));
        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(com.bablsoft.accessflow.ai.api.AiRateLimitExceededException.class);

        verify(aiConfigRepository, never())
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(any());
    }

    @Test
    void analyzeRethrowsFallbackGuardrailViolationInsteadOfSwallowingIt() {
        var orgId = UUID.randomUUID();
        var fallbackId = UUID.randomUUID();
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(new AiAnalysisException("down")));
        seedCache(fallbackId, new ThrowingStrategy(
                new com.bablsoft.accessflow.ai.api.AiGuardrailViolationException("blocked", "p.*n")));
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(fallbackEntity(AI_CONFIG_ID, orgId, "Primary", null)));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of(fallbackEntity(fallbackId, orgId, "Fallback", 0)));

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(com.bablsoft.accessflow.ai.api.AiGuardrailViolationException.class);
    }

    @Test
    void analyzeFailsOverWhenKeylessPrimaryCannotBuildDelegate() {
        var orgId = UUID.randomUUID();
        var fallbackId = UUID.randomUUID();
        var primaryEntity = entityWithoutKey(AiProviderType.ANTHROPIC);
        primaryEntity.setOrganizationId(orgId);
        var fallbackEntity = fallbackEntity(fallbackId, orgId, "Local Ollama", 0);
        fallbackEntity.setProvider(AiProviderType.OLLAMA);
        fallbackEntity.setModel("test-model");
        when(aiConfigRepository.findById(AI_CONFIG_ID)).thenReturn(Optional.of(primaryEntity));
        when(aiConfigRepository.findById(fallbackId)).thenReturn(Optional.of(fallbackEntity));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of(fallbackEntity));
        when(messageSource.getMessage(eq("error.ai.not_configured"), any(), any(Locale.class)))
                .thenReturn("AI is not configured");
        when(chatModelFactory.ollama(eq("http://localhost:11434"), eq("test-model"), anyInt()))
                .thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(successChatResponse());

        var result = holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        // The keyless ANTHROPIC primary fails while building its delegate; the offline Ollama
        // fallback is built for real and serves the request — the air-gapped resilience story.
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OLLAMA);
    }

    @Test
    void generateSqlFailsOverToFallbackConfig() {
        var orgId = UUID.randomUUID();
        var fallbackId = UUID.randomUUID();
        var fallback = new SucceedingStrategy();
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(new AiAnalysisException("down")));
        seedCache(fallbackId, fallback);
        when(aiConfigRepository.findById(AI_CONFIG_ID))
                .thenReturn(Optional.of(fallbackEntity(AI_CONFIG_ID, orgId, "Primary", null)));
        when(aiConfigRepository
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(orgId))
                .thenReturn(List.of(fallbackEntity(fallbackId, orgId, "Fallback", 0)));

        var result = holder.generateSql("all orders", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(result.sql()).isEqualTo("SELECT 1");
        assertThat(fallback.generateSqlCalls).isEqualTo(1);
    }

    @Test
    void analyzeRethrowsPrimaryFailureWhenFailedConfigRowIsGone() {
        seedCache(AI_CONFIG_ID, new ThrowingStrategy(new AiAnalysisException("down")));
        when(aiConfigRepository.findById(AI_CONFIG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holder.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessage("down");
        verify(aiConfigRepository, never())
                .findByOrganizationIdAndFallbackPriorityNotNullOrderByFallbackPriorityAscNameAsc(any());
    }

    private AiConfigEntity fallbackEntity(UUID id, UUID orgId, String name, Integer priority) {
        var entity = new AiConfigEntity();
        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setName(name);
        entity.setProvider(AiProviderType.OLLAMA);
        entity.setModel("test-model");
        entity.setFallbackPriority(priority);
        return entity;
    }

    private static class ThrowingStrategy implements com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy {
        private final RuntimeException failure;
        int calls = 0;

        ThrowingStrategy(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                String costEstimateContext, String language, UUID aiConfigId) {
            calls++;
            throw failure;
        }

        @Override
        public com.bablsoft.accessflow.ai.api.GeneratedSqlResult generateSql(String prompt,
                DbType dbType, String schemaContext, String language, UUID aiConfigId) {
            calls++;
            throw failure;
        }
    }

    private static class SucceedingStrategy implements com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy {
        int calls = 0;
        int generateSqlCalls = 0;

        @Override
        public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                String costEstimateContext, String language, UUID aiConfigId) {
            calls++;
            return new AiAnalysisResult(10, com.bablsoft.accessflow.core.api.RiskLevel.LOW, "ok",
                    List.of(), false, null, AiProviderType.OLLAMA, "test-model", 10, 5, List.of());
        }

        @Override
        public com.bablsoft.accessflow.ai.api.GeneratedSqlResult generateSql(String prompt,
                DbType dbType, String schemaContext, String language, UUID aiConfigId) {
            generateSqlCalls++;
            return new com.bablsoft.accessflow.ai.api.GeneratedSqlResult("SELECT 1",
                    AiProviderType.OLLAMA, "test-model", 0, 0);
        }
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

    private ChatResponse generatedSqlChatResponse() {
        var generation = new Generation(new AssistantMessage("{\"sql\":\"SELECT 1\"}"));
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
        public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                                        String costEstimateContext, String language, UUID aiConfigId) {
            calls++;
            return null;
        }

        @Override
        public com.bablsoft.accessflow.ai.api.GeneratedSqlResult generateSql(String prompt,
                DbType dbType, String schemaContext, String language, UUID aiConfigId) {
            return new com.bablsoft.accessflow.ai.api.GeneratedSqlResult("SELECT 1",
                    AiProviderType.ANTHROPIC, "stub-model", 0, 0);
        }
    }
}
