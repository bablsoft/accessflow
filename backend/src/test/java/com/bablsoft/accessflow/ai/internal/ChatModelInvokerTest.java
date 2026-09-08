package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

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
}
