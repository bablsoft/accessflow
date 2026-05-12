package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
class AnthropicAnalyzerStrategyTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    private static final String SUCCESS_JSON = """
            {"risk_score":85,"risk_level":"HIGH","summary":"DELETE without WHERE","issues":[],"missing_indexes_detected":false,"affects_row_estimate":null}""";

    @Mock ChatModel chatModel;
    @Spy SystemPromptRenderer renderer = new SystemPromptRenderer();
    @Spy AiResponseParser parser = new AiResponseParser(JsonMapper.builder().build());

    @InjectMocks AnthropicAnalyzerStrategy strategy;

    private static ChatResponse buildResponse(String text, int inputTokens, int outputTokens, String model) {
        var generation = new Generation(new AssistantMessage(text));
        var metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(inputTokens, outputTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void analyzeBuildsPromptAndParsesSuccessfulResponse() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(buildResponse(SUCCESS_JSON, 250, 80, "claude-sonnet-4-20250514"));

        var result = strategy.analyze("DELETE FROM users", DbType.POSTGRESQL, "public.users(id int pk)", "es", ORG_ID);

        assertThat(result.riskScore()).isEqualTo(85);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(result.aiModel()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(result.promptTokens()).isEqualTo(250);
        assertThat(result.completionTokens()).isEqualTo(80);

        var promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(chatModel).call(promptCaptor.capture());
        var messages = promptCaptor.getValue().getInstructions();
        assertThat(messages).hasSize(2);
        Message system = messages.get(0);
        Message user = messages.get(1);
        assertThat(system.getText()).contains("You analyze SQL");
        assertThat(user.getText()).contains("DELETE FROM users");
        assertThat(user.getText()).contains("Database type: POSTGRESQL");
        assertThat(user.getText()).contains("public.users(id int pk)");
        assertThat(user.getText()).contains("Respond in: Español");
    }

    @Test
    void analyzeWrapsRuntimeExceptionAsAnalysisException() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("upstream"));

        assertThatThrownBy(() -> strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("upstream");
    }

    @Test
    void analyzeFailsWhenResponseTextIsBlank() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(buildResponse("   ", 1, 1, "claude-sonnet-4-20250514"));

        assertThatThrownBy(() -> strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("empty message");
    }

    @Test
    void analyzePropagatesParseFailureWhenContentIsMalformed() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(buildResponse("not valid json", 10, 5, "claude-sonnet-4-20250514"));

        assertThatThrownBy(() -> strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    @Test
    void analyzeUsesZerosWhenMetadataAbsent() {
        var generation = new Generation(new AssistantMessage(SUCCESS_JSON));
        var responseWithoutMetadata = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(responseWithoutMetadata);

        var result = strategy.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", ORG_ID);

        assertThat(result.promptTokens()).isZero();
        assertThat(result.completionTokens()).isZero();
        assertThat(result.aiModel()).isEmpty();
    }
}
