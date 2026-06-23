package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiConfigModelCommand;
import com.bablsoft.accessflow.core.api.AiProviderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One orchestration member in a create/update AI-config request (AF-450). {@code id} is present when
 * editing an existing member (so its masked key is preserved). {@code apiKey} follows the parent's
 * masking semantics ({@code "********"} keeps the stored key).
 */
record AiConfigModelRequest(
        UUID id,
        @NotNull(message = "{validation.ai_config.model_member.provider_required}") AiProviderType provider,
        @Size(min = 1, max = 100, message = "{validation.ai_config.model_member.model}") String model,
        @Size(max = 500, message = "{validation.ai_config.model_member.endpoint_max}") String endpoint,
        @Size(max = 4096, message = "{validation.ai_config.model_member.api_key_max}") String apiKey,
        @DecimalMin(value = "0.0", inclusive = false,
                message = "{validation.ai_config.model_member.weight_positive}") Double weight,
        Boolean enabled) {

    AiConfigModelCommand toCommand() {
        return new AiConfigModelCommand(id, provider, model, endpoint, apiKey, weight, enabled);
    }
}
