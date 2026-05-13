package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.SystemSmtpView;

import java.time.Instant;
import java.util.UUID;

public record SystemSmtpResponse(
        UUID organizationId,
        String host,
        int port,
        String username,
        String smtpPassword,
        boolean tls,
        String fromAddress,
        String fromName,
        Instant updatedAt
) {

    public static final String MASKED_PASSWORD = "********";

    public static SystemSmtpResponse from(SystemSmtpView view, boolean passwordSet) {
        return new SystemSmtpResponse(
                view.organizationId(),
                view.host(),
                view.port(),
                view.username(),
                passwordSet ? MASKED_PASSWORD : null,
                view.tls(),
                view.fromAddress(),
                view.fromName(),
                view.updatedAt());
    }
}
