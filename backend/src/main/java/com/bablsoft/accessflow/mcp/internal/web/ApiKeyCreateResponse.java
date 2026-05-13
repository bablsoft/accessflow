package com.bablsoft.accessflow.mcp.internal.web;

import com.bablsoft.accessflow.mcp.api.IssuedApiKey;

/**
 * Response body for {@code POST /api/v1/me/api-keys}. The {@code rawKey} field is the only point
 * at which the plaintext key is exposed — the frontend must surface it once and discard.
 */
public record ApiKeyCreateResponse(ApiKeyResponse apiKey, String rawKey) {

    public static ApiKeyCreateResponse from(IssuedApiKey issued) {
        return new ApiKeyCreateResponse(ApiKeyResponse.from(issued.view()), issued.rawKey());
    }
}
