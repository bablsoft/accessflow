package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiConfigModelView;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.core.api.AiProviderType;

import java.util.UUID;

/** One orchestration member in an AI-config response (AF-450). The key is masked, never returned. */
record AiConfigModelResponse(
        UUID id,
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        double weight,
        boolean enabled) {

    static AiConfigModelResponse from(AiConfigModelView view) {
        return new AiConfigModelResponse(
                view.id(),
                view.provider(),
                view.model(),
                view.endpoint(),
                view.apiKeyMasked() ? UpdateAiConfigCommand.MASKED_API_KEY : null,
                view.weight(),
                view.enabled());
    }
}
