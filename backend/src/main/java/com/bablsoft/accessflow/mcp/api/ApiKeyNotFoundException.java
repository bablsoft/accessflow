package com.bablsoft.accessflow.mcp.api;

import java.util.UUID;

public class ApiKeyNotFoundException extends RuntimeException {

    private final UUID apiKeyId;

    public ApiKeyNotFoundException(UUID apiKeyId) {
        super("API key not found: " + apiKeyId);
        this.apiKeyId = apiKeyId;
    }

    public UUID apiKeyId() {
        return apiKeyId;
    }
}
