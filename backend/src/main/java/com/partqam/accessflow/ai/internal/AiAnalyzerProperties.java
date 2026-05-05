package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.core.api.AiProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AccessFlow-level AI settings. Provider-specific options (model, API key, base URL, max tokens,
 * timeouts) live under Spring AI's own {@code spring.ai.<provider>.*} namespace; this record
 * carries only the cross-cutting choice of which provider to activate.
 */
@ConfigurationProperties("accessflow.ai")
record AiAnalyzerProperties(AiProviderType provider) {

    AiAnalyzerProperties {
        if (provider == null) {
            provider = AiProviderType.ANTHROPIC;
        }
    }
}
