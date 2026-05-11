package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.ai.api.CreateAiConfigCommand;
import com.partqam.accessflow.core.api.AiProviderType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateAiConfigRequest(
        @NotBlank(message = "{validation.ai_config.name.required}")
        @Size(min = 1, max = 255, message = "{validation.ai_config.name.size}") String name,
        @NotNull(message = "{validation.ai_config.provider.required}") AiProviderType provider,
        @NotBlank(message = "{validation.ai_config.model.required}")
        @Size(max = 100, message = "{validation.ai_config.model.max}") String model,
        @Size(max = 500, message = "{validation.ai_config.endpoint.max}") String endpoint,
        @Size(max = 4096, message = "{validation.ai_config.api_key.max}") String apiKey,
        @Min(value = 1000, message = "{validation.ai_config.timeout_ms.range}")
        @Max(value = 600000, message = "{validation.ai_config.timeout_ms.range}") Integer timeoutMs,
        @Min(value = 100, message = "{validation.ai_config.max_prompt_tokens.range}")
        @Max(value = 200000, message = "{validation.ai_config.max_prompt_tokens.range}") Integer maxPromptTokens,
        @Min(value = 100, message = "{validation.ai_config.max_completion_tokens.range}")
        @Max(value = 200000, message = "{validation.ai_config.max_completion_tokens.range}") Integer maxCompletionTokens) {

    CreateAiConfigCommand toCommand() {
        return new CreateAiConfigCommand(
                name,
                provider,
                model,
                endpoint,
                apiKey,
                timeoutMs,
                maxPromptTokens,
                maxCompletionTokens);
    }
}
