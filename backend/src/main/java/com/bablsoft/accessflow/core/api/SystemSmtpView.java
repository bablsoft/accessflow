package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record SystemSmtpView(
        UUID organizationId,
        String host,
        int port,
        String username,
        boolean tls,
        String fromAddress,
        String fromName,
        Instant updatedAt
) {}
