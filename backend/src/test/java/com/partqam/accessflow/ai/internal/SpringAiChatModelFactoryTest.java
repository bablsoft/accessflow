package com.partqam.accessflow.ai.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the factory should build a {@code ChatModel} for each provider without throwing
 * when handed sane inputs. We intentionally do not call the resulting model — that would hit
 * the network. Coverage focus: every branch in {@link SpringAiChatModelFactory} executes.
 */
class SpringAiChatModelFactoryTest {

    private final SpringAiChatModelFactory factory = new SpringAiChatModelFactory();

    @Test
    void anthropicBuildsChatModel() {
        var model = factory.anthropic("sk-test", "https://api.anthropic.com",
                "claude-sonnet-4-20250514", 1000, 30_000);
        assertThat(model).isNotNull();
    }

    @Test
    void openAiBuildsChatModel() {
        var model = factory.openAi("sk-test", "https://api.openai.com",
                "gpt-4o", 1000, 30_000);
        assertThat(model).isNotNull();
    }

    @Test
    void ollamaBuildsChatModel() {
        var model = factory.ollama("http://localhost:11434", "llama3.1", 1000);
        assertThat(model).isNotNull();
    }
}
