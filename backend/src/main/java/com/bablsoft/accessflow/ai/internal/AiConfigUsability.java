package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiProviderCapabilities;
import com.bablsoft.accessflow.core.api.AiProviderType;

/**
 * Whether an {@code ai_config} row can actually be used to analyze a query. Two callers ask the
 * question — {@link AiAnalyzerStrategyHolder}, picking the row to run with, and
 * {@code DefaultAiConfigLookupService}, answering the setup-progress checklist — and before AF-918
 * each carried its own hand-maintained copy of the answer.
 */
final class AiConfigUsability {

    private AiConfigUsability() {
    }

    /**
     * A row is usable when its provider can drive a chat model at all, and either runs keyless
     * (self-hosted Ollama / OpenAI-compatible / local TGI, whose endpoint has a default or is
     * enforced at create time) or has an API key stored.
     */
    static boolean isUsable(AiProviderType provider, String apiKeyCiphertext) {
        if (provider == null || !AiProviderCapabilities.supportsChat(provider)) {
            return false;
        }
        return AiProviderCapabilities.keylessCapable(provider)
                || (apiKeyCiphertext != null && !apiKeyCiphertext.isBlank());
    }
}
