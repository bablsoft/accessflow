package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalyzerPropertiesTest {

    @Test
    void providerDefaultsToAnthropicWhenNull() {
        assertThat(new AiAnalyzerProperties(null).provider()).isEqualTo(AiProviderType.ANTHROPIC);
    }

    @Test
    void providerIsHonoredWhenSet() {
        assertThat(new AiAnalyzerProperties(AiProviderType.OPENAI).provider())
                .isEqualTo(AiProviderType.OPENAI);
        assertThat(new AiAnalyzerProperties(AiProviderType.OLLAMA).provider())
                .isEqualTo(AiProviderType.OLLAMA);
    }
}
