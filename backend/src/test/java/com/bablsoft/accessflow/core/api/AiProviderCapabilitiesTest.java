package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classifies every {@link AiProviderType} on every axis. Parameterized over {@code values()} on
 * purpose: a new provider that nobody classified here fails this test rather than inheriting a
 * plausible-looking default somewhere in the wiring.
 */
class AiProviderCapabilitiesTest {

    private static final EnumSet<AiProviderType> CHAT = EnumSet.of(AiProviderType.OPENAI,
            AiProviderType.ANTHROPIC, AiProviderType.OLLAMA, AiProviderType.OPENAI_COMPATIBLE,
            AiProviderType.HUGGING_FACE);
    private static final EnumSet<AiProviderType> EMBEDDING = EnumSet.of(AiProviderType.OPENAI,
            AiProviderType.OLLAMA, AiProviderType.OPENAI_COMPATIBLE, AiProviderType.HUGGING_FACE,
            AiProviderType.VOYAGE);
    private static final EnumSet<AiProviderType> KEYLESS = EnumSet.of(AiProviderType.OLLAMA,
            AiProviderType.OPENAI_COMPATIBLE, AiProviderType.HUGGING_FACE);
    private static final EnumSet<AiProviderType> ENDPOINT = EnumSet.of(AiProviderType.OPENAI_COMPATIBLE);

    @ParameterizedTest
    @EnumSource(AiProviderType.class)
    void classifiesEveryProviderOnEveryAxis(AiProviderType provider) {
        assertThat(AiProviderCapabilities.supportsChat(provider)).isEqualTo(CHAT.contains(provider));
        assertThat(AiProviderCapabilities.supportsEmbedding(provider))
                .isEqualTo(EMBEDDING.contains(provider));
        assertThat(AiProviderCapabilities.keylessCapable(provider)).isEqualTo(KEYLESS.contains(provider));
        assertThat(AiProviderCapabilities.requiresEndpoint(provider))
                .isEqualTo(ENDPOINT.contains(provider));
    }

    @ParameterizedTest
    @EnumSource(AiProviderType.class)
    void everyProviderCanDoAtLeastOneOfChatOrEmbedding(AiProviderType provider) {
        assertThat(AiProviderCapabilities.supportsChat(provider)
                || AiProviderCapabilities.supportsEmbedding(provider)).isTrue();
    }

    @Test
    void anthropicAndVoyageAreExactMirrors() {
        assertThat(AiProviderCapabilities.supportsChat(AiProviderType.ANTHROPIC)).isTrue();
        assertThat(AiProviderCapabilities.supportsEmbedding(AiProviderType.ANTHROPIC)).isFalse();
        assertThat(AiProviderCapabilities.supportsChat(AiProviderType.VOYAGE)).isFalse();
        assertThat(AiProviderCapabilities.supportsEmbedding(AiProviderType.VOYAGE)).isTrue();
    }
}
