package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.LangfuseConfigView;
import com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand;

import java.time.Instant;
import java.util.UUID;

record LangfuseConfigResponse(
        UUID id,
        UUID organizationId,
        boolean enabled,
        String host,
        String publicKey,
        String secretKey,
        boolean tracingEnabled,
        boolean promptManagementEnabled,
        Instant createdAt,
        Instant updatedAt) {

    static LangfuseConfigResponse from(LangfuseConfigView view) {
        return new LangfuseConfigResponse(
                view.id(),
                view.organizationId(),
                view.enabled(),
                view.host(),
                view.publicKey(),
                view.secretKeyConfigured() ? UpdateLangfuseConfigCommand.MASKED_SECRET : null,
                view.tracingEnabled(),
                view.promptManagementEnabled(),
                view.createdAt(),
                view.updatedAt());
    }
}
