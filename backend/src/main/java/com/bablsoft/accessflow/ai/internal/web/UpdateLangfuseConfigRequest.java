package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand;
import jakarta.validation.constraints.Size;

record UpdateLangfuseConfigRequest(
        Boolean enabled,
        @Size(max = 500, message = "{validation.langfuse_config.host.max}") String host,
        @Size(max = 255, message = "{validation.langfuse_config.public_key.max}") String publicKey,
        @Size(max = 512, message = "{validation.langfuse_config.secret_key.max}") String secretKey,
        Boolean tracingEnabled,
        Boolean promptManagementEnabled) {

    UpdateLangfuseConfigCommand toCommand() {
        return new UpdateLangfuseConfigCommand(
                enabled,
                host,
                publicKey,
                secretKey,
                tracingEnabled,
                promptManagementEnabled);
    }
}
