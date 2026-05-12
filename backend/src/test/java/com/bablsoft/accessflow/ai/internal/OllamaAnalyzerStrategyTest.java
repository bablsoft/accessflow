package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OllamaAnalyzerStrategyTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final String SUCCESS_JSON = """
            {"risk_score":10,"risk_level":"LOW","summary":"safe","issues":[],"missing_indexes_detected":false,"affects_row_estimate":null}""";

    @Mock ChatModel chatModel;
    @Spy SystemPromptRenderer renderer = new SystemPromptRenderer();
    @Spy AiResponseParser parser = new AiResponseParser(JsonMapper.builder().build());

    @InjectMocks OllamaAnalyzerStrategy strategy;

    private static ChatResponse buildResponse(String text, int promptTokens, int completionTokens, String model) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void analyzeReturnsLowRiskAndTagsOllamaProvider() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(buildResponse(SUCCESS_JSON, 30, 10, "llama3.1:70b"));

        var result = strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.OLLAMA);
        assertThat(result.aiModel()).isEqualTo("llama3.1:70b");
    }

    @Test
    void analyzeWrapsRuntimeExceptionAsAnalysisException() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void analyzeFailsWhenResponseIsNull() {
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        assertThatThrownBy(() -> strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void analyzeFailsWhenResponseTextIsBlank() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(buildResponse(" ", 1, 1, "llama3.1:70b"));

        assertThatThrownBy(() -> strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("empty message");
    }

    @Test
    void analyzePropagatesParseFailureWhenContentIsMalformed() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(buildResponse("xyz", 1, 1, "llama3.1:70b"));

        assertThatThrownBy(() -> strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisParseException.class);
    }
}
