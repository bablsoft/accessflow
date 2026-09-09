package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiBudgetExceededException;
import com.bablsoft.accessflow.ai.api.AiConfigOrchestrationInvalidException;
import com.bablsoft.accessflow.ai.api.AiConfigProviderInvalidException;
import com.bablsoft.accessflow.ai.api.AiConfigRagInvalidException;
import com.bablsoft.accessflow.ai.api.AiGuardrailViolationException;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.HelpCorpusUnavailableException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentIngestException;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAnalysisExceptionHandlerTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private final AiAnalysisExceptionHandler handler = new AiAnalysisExceptionHandler(messageSource);

    @Test
    void mapsRagInvalidToBadRequestWithResolvedKey() {
        when(messageSource.getMessage(eq("error.ai_config.rag.store_type_required"), any(), any(Locale.class)))
                .thenReturn("A vector store type is required");

        var pd = handler.handleAiConfigRagInvalid(
                new AiConfigRagInvalidException("error.ai_config.rag.store_type_required"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "RAG_CONFIG_INVALID");
        assertThat(pd.getDetail()).isEqualTo("A vector store type is required");
    }

    @Test
    void mapsProviderInvalidToItsOwnErrorCode() {
        when(messageSource.getMessage(eq("error.ai_config.provider_not_chat_capable"), any(),
                any(Locale.class))).thenReturn("Voyage AI provides embeddings only");

        var pd = handler.handleAiConfigProviderInvalid(
                new AiConfigProviderInvalidException("error.ai_config.provider_not_chat_capable"));

        // Distinct from RAG_CONFIG_INVALID: the offending field is `provider`, not a RAG setting.
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "AI_CONFIG_PROVIDER_INVALID");
        assertThat(pd.getDetail()).isEqualTo("Voyage AI provides embeddings only");
    }

    @Test
    void passesRagMessageArgumentsThroughToTheResolvedDetail() {
        when(messageSource.getMessage(
                eq("error.ai_config.rag.embedding_dimensions_pgvector_mismatch"),
                any(), any(Locale.class))).thenReturn("1024 vs 1536");

        var pd = handler.handleAiConfigRagInvalid(new AiConfigRagInvalidException(
                "error.ai_config.rag.embedding_dimensions_pgvector_mismatch", 1024, 1536));

        assertThat(pd.getDetail()).isEqualTo("1024 vs 1536");
        verify(messageSource).getMessage(
                eq("error.ai_config.rag.embedding_dimensions_pgvector_mismatch"),
                eq(new Object[] {1024, 1536}), any(Locale.class));
    }

    @Test
    void mapsAnUnloadableHelpCorpusToItsOwnErrorCode() {
        when(messageSource.getMessage(eq("error.help_agent.corpus_missing"), any(), any(Locale.class)))
                .thenReturn("The bundled documentation corpus could not be loaded");

        var pd = handler.handleHelpCorpusUnavailable(new HelpCorpusUnavailableException(
                "error.help_agent.corpus_missing", "corpus.jsonl is missing"));

        // A distinct code, not HELP_AGENT_CONFIG_INVALID: nothing the admin can change on the form
        // fixes a corpus the build did not ship.
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "HELP_CORPUS_MISSING");
        assertThat(pd.getDetail()).isEqualTo("The bundled documentation corpus could not be loaded");
        // The classpath path and digest in getMessage() are diagnostic; they never reach the response.
        assertThat(pd.getDetail()).doesNotContain("corpus.jsonl");
    }

    @Test
    void mapsParseFailureToUnprocessableEntityWithReason() {
        when(messageSource.getMessage(eq("error.ai_response_invalid"), any(), any(Locale.class)))
                .thenReturn("AI provider returned an invalid or unparseable response");

        var pd = handler.handleAiAnalysisParse(new AiAnalysisParseException(
                "Generated query did not parse for MONGODB: unsupported operation 'mapReduce'"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getProperties()).containsEntry("error", "AI_RESPONSE_INVALID");
        assertThat(pd.getProperties()).containsEntry("reason",
                "Generated query did not parse for MONGODB: unsupported operation 'mapReduce'");
        assertThat(pd.getDetail()).isEqualTo("AI provider returned an invalid or unparseable response");
    }

    @Test
    void mapsProviderFailureToServiceUnavailableWithoutLeakingMessage() {
        when(messageSource.getMessage(eq("error.ai_provider_unavailable"), any(), any(Locale.class)))
                .thenReturn("AI provider is currently unavailable");

        var pd = handler.handleAiAnalysis(new AiAnalysisException("Anthropic API call failed: 500"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getProperties()).containsEntry("error", "AI_PROVIDER_UNAVAILABLE");
        assertThat(pd.getProperties()).doesNotContainKey("reason");
        assertThat(pd.getDetail()).isEqualTo("AI provider is currently unavailable");
    }

    @Test
    void mapsRateLimitToTooManyRequestsWithLimitAndRetryAfter() {
        when(messageSource.getMessage(eq("error.ai.rate_limit_exceeded"), any(), any(Locale.class)))
                .thenReturn("Too many AI analysis requests");

        var pd = handler.handleAiRateLimitExceeded(new AiRateLimitExceededException(30, 60));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getProperties()).containsEntry("error", "AI_RATE_LIMIT_EXCEEDED");
        assertThat(pd.getProperties()).containsEntry("limit", 30);
        assertThat(pd.getProperties()).containsEntry("retryAfterSeconds", 60L);
        assertThat(pd.getDetail()).isEqualTo("Too many AI analysis requests");
    }

    @Test
    void mapsBudgetToTooManyRequests() {
        when(messageSource.getMessage(eq("error.ai.budget_exceeded"), any(), any(Locale.class)))
                .thenReturn("Monthly AI budget reached");

        var pd = handler.handleAiBudgetExceeded(new AiBudgetExceededException(1000, 1000));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getProperties()).containsEntry("error", "AI_BUDGET_EXCEEDED");
        assertThat(pd.getDetail()).isEqualTo("Monthly AI budget reached");
    }

    @Test
    void mapsKnowledgeDocumentNotFoundToNotFound() {
        when(messageSource.getMessage(eq("error.knowledge_document.not_found"), any(), any(Locale.class)))
                .thenReturn("Knowledge document not found");

        var pd = handler.handleKnowledgeDocumentNotFound(
                new KnowledgeDocumentNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "KNOWLEDGE_DOCUMENT_NOT_FOUND");
    }

    @Test
    void mapsGuardrailViolationToUnprocessableEntity() {
        when(messageSource.getMessage(eq("error.ai.guardrail_blocked"), any(), any(Locale.class)))
                .thenReturn("This query was blocked by a guardrail");

        var pd = handler.handleAiGuardrailViolation(
                new AiGuardrailViolationException("blocked", "drop\\s+table"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getProperties()).containsEntry("error", "AI_GUARDRAIL_BLOCKED");
        assertThat(pd.getDetail()).isEqualTo("This query was blocked by a guardrail");
    }

    @Test
    void mapsOrchestrationInvalidToBadRequestWithResolvedKey() {
        when(messageSource.getMessage(eq("error.ai_config.guardrail_pattern_invalid"), any(), any(Locale.class)))
                .thenReturn("A guardrail pattern is not a valid regular expression");

        var pd = handler.handleAiConfigOrchestrationInvalid(
                new AiConfigOrchestrationInvalidException("error.ai_config.guardrail_pattern_invalid"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "AI_CONFIG_ORCHESTRATION_INVALID");
        assertThat(pd.getDetail()).isEqualTo("A guardrail pattern is not a valid regular expression");
    }

    @Test
    void mapsIngestFailureToBadGateway() {
        when(messageSource.getMessage(eq("error.ai_config.rag.ingest_failed"), any(), any(Locale.class)))
                .thenReturn("Failed to embed and store the knowledge document");

        var pd = handler.handleKnowledgeDocumentIngest(
                new KnowledgeDocumentIngestException("boom", new RuntimeException("embed down")));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(pd.getProperties()).containsEntry("error", "RAG_INGEST_FAILED");
    }
}
