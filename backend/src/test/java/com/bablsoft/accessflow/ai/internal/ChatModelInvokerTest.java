package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
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

    @Test
    void throwsWhenTextBlank() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("  ")))));

        assertThatThrownBy(() -> ChatModelInvoker.invoke(chatModel, "s", "u", "Ollama"))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("empty message");
    }
}
