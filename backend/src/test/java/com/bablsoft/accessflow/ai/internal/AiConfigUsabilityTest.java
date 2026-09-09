package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigUsabilityTest {

    @Test
    void keylessProvidersAreUsableWithoutAKey() {
        assertThat(AiConfigUsability.isUsable(AiProviderType.OLLAMA, null)).isTrue();
        assertThat(AiConfigUsability.isUsable(AiProviderType.OPENAI_COMPATIBLE, "  ")).isTrue();
        assertThat(AiConfigUsability.isUsable(AiProviderType.HUGGING_FACE, null)).isTrue();
    }

    @Test
    void hostedChatProvidersNeedAKey() {
        assertThat(AiConfigUsability.isUsable(AiProviderType.OPENAI, null)).isFalse();
        assertThat(AiConfigUsability.isUsable(AiProviderType.ANTHROPIC, "   ")).isFalse();
        assertThat(AiConfigUsability.isUsable(AiProviderType.ANTHROPIC, "ENC(k)")).isTrue();
    }

    @Test
    void embeddingOnlyProviderIsNeverUsableEvenWithAKey() {
        assertThat(AiConfigUsability.isUsable(AiProviderType.VOYAGE, "ENC(k)")).isFalse();
    }

    @Test
    void nullProviderIsNotUsable() {
        assertThat(AiConfigUsability.isUsable(null, "ENC(k)")).isFalse();
    }
}
