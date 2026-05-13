package com.bablsoft.accessflow.core.api;

public record SaveSystemSmtpCommand(
        String host,
        int port,
        String username,
        String plaintextPassword,
        boolean tls,
        String fromAddress,
        String fromName
) {}
