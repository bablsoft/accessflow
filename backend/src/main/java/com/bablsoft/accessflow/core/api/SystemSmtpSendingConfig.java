package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public record SystemSmtpSendingConfig(
        UUID organizationId,
        String host,
        int port,
        String username,
        String plaintextPassword,
        boolean tls,
        String fromAddress,
        String fromName
) {}
