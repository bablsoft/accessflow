package com.bablsoft.accessflow.ai.internal;

/**
 * Decrypted, ready-to-use Langfuse settings for one organization. Built by {@link LangfuseConfigResolver}
 * and consumed by {@link LangfuseClient} / {@link DefaultLangfuseTracer} / {@link DefaultLangfusePromptProvider}.
 * The {@code host} is always a normalized base URL ending in {@code /}.
 */
record ResolvedLangfuseConfig(
        String host,
        String publicKey,
        String secretKey,
        boolean tracingEnabled,
        boolean promptManagementEnabled) {
}
