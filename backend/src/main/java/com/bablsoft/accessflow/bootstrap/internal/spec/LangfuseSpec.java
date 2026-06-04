package com.bablsoft.accessflow.bootstrap.internal.spec;

public record LangfuseSpec(
        boolean enabled,
        String host,
        String publicKey,
        String secretKey,
        Boolean tracingEnabled,
        Boolean promptManagementEnabled
) {
}
