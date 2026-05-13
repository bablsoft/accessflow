package com.bablsoft.accessflow.security.api;

/**
 * Result of issuing a new API key. The {@code rawKey} is the plaintext value the caller must
 * surface to the user exactly once — it is never persisted and cannot be recovered later.
 */
public record IssuedApiKey(ApiKeyView view, String rawKey) {}
