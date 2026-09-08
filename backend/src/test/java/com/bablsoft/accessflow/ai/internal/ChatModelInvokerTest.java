package com.bablsoft.accessflow.ai.internal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatModelInvokerTest {

    @Mock ChatModel chatModel;

    @Test
    void extractsTextModelAndUsage() {
        var metadata = ChatResponseMetadata.builder()
                .model("gpt-4o")
                .usage(new DefaultUsage(11, 4))
                .build();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("hello"))), metadata));

        var call = ChatModelInvoker.invoke(chatModel, "system", "user", "OpenAI");

        assertThat(call.text()).isEqualTo("hello");
        assertThat(call.model()).isEqualTo("gpt-4o");
        assertThat(call.promptTokens()).isEqualTo(11);
        assertThat(call.completionTokens()).isEqualTo(4);
    }

    @Test
    void usesZerosWhenMetadataAbsent() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("hi")))));

        var call = ChatModelInvoker.invoke(chatModel, "system", "user", "Ollama");

        assertThat(call.promptTokens()).isZero();
        assertThat(call.completionTokens()).isZero();
        assertThat(call.model()).isEmpty();
    }

    @Test
    void wrapsRuntimeExceptionAsAnalysisException() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("rate limited"));

        assertThatThrownBy(() -> ChatModelInvoker.invoke(chatModel, "s", "u", "OpenAI"))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("OpenAI API call failed")
                .hasMessageContaining("rate limited");
    }

    @Test
    void throwsWhenResponseNull() {
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        assertThatThrownBy(() -> ChatModelInvoker.invoke(chatModel, "s", "u", "Anthropic"))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("empty response");
    }

    /**
     * The multi-turn overload help chat calls (AF-903): every message is passed through in order,
     * and the response is read exactly as the single-turn path reads it.
     */
    @Test
    void listOverloadPassesEveryMessageThroughInOrder() {
        var captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture()))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))),
                        ChatResponseMetadata.builder().model("claude").usage(new DefaultUsage(7, 3)).build()));

        var call = ChatModelInvoker.invoke(chatModel, List.of(
                new SystemMessage("rules"),
                new UserMessage("first"),
                new AssistantMessage("reply"),
                new UserMessage("second")), "Anthropic");

        assertThat(call.text()).isEqualTo("answer");
        assertThat(call.promptTokens()).isEqualTo(7);
        assertThat(captor.getValue().getInstructions())
                .extracting(Message::getText)
                .containsExactly("rules", "first", "reply", "second");
    }

    /**
     * The 3-arg callers every provider adapter uses must be unchanged by the overload: still exactly
     * one system message followed by one user message, in that order.
     */
    @Test
    void singleTurnCallStillSendsSystemThenUser() {
        var captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture()))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

        ChatModelInvoker.invoke(chatModel, "preamble", "prompt", "OpenAI");

        assertThat(captor.getValue().getInstructions())
                .extracting(Message::getClass, Message::getText)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SystemMessage.class, "preamble"),
                        org.assertj.core.groups.Tuple.tuple(UserMessage.class, "prompt"));
    }

    @Test
    void throwsWhenTextBlank() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("  ")))));

        assertThatThrownBy(() -> ChatModelInvoker.invoke(chatModel, "s", "u", "Ollama"))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("empty message");
    }

    /**
     * The shape a thinking Anthropic model returns: Spring AI emits one generation per reasoning
     * block and appends the aggregated answer <em>last</em>. Reading the first generation returned the
     * reasoning as the answer; the answer is the last non-blank one.
     */
    @Test
    void listOverloadReturnsTheAnswerNotThePrecedingThinkingBlock() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .content("Let me work through the docs...")
                        .properties(Map.of("signature", "sig"))
                        .build()),
                new Generation(new AssistantMessage("Submit the query from the editor.")))));

        var call = ChatModelInvoker.invoke(chatModel, "s", "u", "Anthropic");

        assertThat(call.text()).isEqualTo("Submit the query from the editor.");
    }

    /**
     * A {@code redacted_thinking} block becomes a generation with properties and no content at all —
     * the exact response that made the help assistant fail on every documentation question while a
     * greeting, which produces no reasoning block, kept working.
     */
    @Test
    void listOverloadSkipsARedactedThinkingGenerationWithNoContent() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(AssistantMessage.builder()
                        .properties(Map.of("data", "redacted"))
                        .build()),
                new Generation(new AssistantMessage("Break-glass needs the can_break_glass grant.")))));

        var call = ChatModelInvoker.invoke(chatModel, "s", "u", "Anthropic");

        assertThat(call.text()).isEqualTo("Break-glass needs the can_break_glass grant.");
    }

    @Test
    void throwsWhenEveryGenerationIsBlank() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().properties(Map.of("data", "r")).build()),
                new Generation(new AssistantMessage("   ")))));

        assertThatThrownBy(() -> ChatModelInvoker.invoke(chatModel, "s", "u", "Anthropic"))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("empty message");
    }

    /**
     * A blank response whose stop reason is the token budget is a different operator problem from a
     * blank response for any other reason, and says so.
     */
    @Test
    void namesTheTokenBudgetWhenATruncatedResponseCarriedNoText() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(""),
                        ChatGenerationMetadata.builder().finishReason("max_tokens").build()))));

        assertThatThrownBy(() -> ChatModelInvoker.invoke(chatModel, "s", "u", "Anthropic"))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("max_completion_tokens");
    }

    @Test
    void returnsATruncatedAnswerUnchanged() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("cut off here"),
                        ChatGenerationMetadata.builder().finishReason("length").build()))));

        assertThat(ChatModelInvoker.invoke(chatModel, "s", "u", "OpenAI").text())
                .isEqualTo("cut off here");
    }

    /**
     * The failure diagnostic reaches an operator log, and the help-chat prompt it describes carries
     * the user's screen, permissions and question. It may name sizes; it may never name content.
     */
    @Test
    void failureDiagnosticNeverCarriesPromptOrCompletionText() {
        var logger = (Logger) LoggerFactory.getLogger(ChatModelInvoker.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("")))));

        try {
            assertThatThrownBy(() -> ChatModelInvoker.invoke(chatModel,
                    "PREAMBLE-SECRET-MARKER", "QUESTION-SECRET-MARKER", "Anthropic"))
                    .isInstanceOf(AiAnalysisException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isNotEmpty();
        var logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logged)
                .doesNotContain("PREAMBLE-SECRET-MARKER")
                .doesNotContain("QUESTION-SECRET-MARKER")
                .contains("generations=1")
                .contains("system_chars=22");
    }
}
