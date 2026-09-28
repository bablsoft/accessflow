package com.bablsoft.accessflow.workflow.internal.web.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Update-decision-hook request (#945): a full replace, except that an absent secret is kept. */
public record UpdateDecisionHookRequest(
        @NotBlank(message = "{validation.decision_hook_name.required}")
        @Size(max = 255, message = "{validation.decision_hook_name.size}")
        String name,

        UUID datasourceId,

        @NotBlank(message = "{validation.decision_hook_endpoint_url.required}")
        @Size(max = 2048, message = "{validation.decision_hook_endpoint_url.size}")
        String endpointUrl,

        @Min(value = 100, message = "{validation.decision_hook_timeout_ms.range}")
        @Max(value = 10000, message = "{validation.decision_hook_timeout_ms.range}")
        Integer timeoutMs,

        @Size(min = 32, max = 512, message = "{validation.decision_hook_secret.size}")
        String secret,

        Boolean includeSql,

        Boolean enabled
) {}
