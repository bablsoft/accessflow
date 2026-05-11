package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.ai.api.AiConfigView;
import com.partqam.accessflow.ai.api.UpdateAiConfigCommand;
import com.partqam.accessflow.core.api.AiProviderType;

import java.time.Instant;
import java.util.UUID;

record AiConfigResponse(
        UUID id,
        UUID organizationId,
        String name,
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        int timeoutMs,
        int maxPromptTokens,
        int maxCompletionTokens,
        int inUseCount,
        Instant createdAt,
        Instant updatedAt) {

    static AiConfigResponse from(AiConfigView view) {
        return new AiConfigResponse(
                view.id(),
                view.organizationId(),
                view.name(),
                view.provider(),
                view.model(),
                view.endpoint(),
                view.apiKeyMasked() ? UpdateAiConfigCommand.MASKED_API_KEY : null,
                view.timeoutMs(),
                view.maxPromptTokens(),
                view.maxCompletionTokens(),
                view.inUseCount(),
                view.createdAt(),
                view.updatedAt());
    }
}
