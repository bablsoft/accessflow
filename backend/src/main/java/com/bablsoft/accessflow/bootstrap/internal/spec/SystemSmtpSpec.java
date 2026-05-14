package com.bablsoft.accessflow.bootstrap.internal.spec;

public record SystemSmtpSpec(
        boolean enabled,
        String host,
        Integer port,
        String username,
        String password,
        Boolean tls,
        String fromAddress,
        String fromName
) {
}
