package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountIssuedKey;

/** {@code rawKey} is the plaintext, shown once — it is never persisted and never audited. */
public record ServiceAccountIssuedKeyResponse(ServiceAccountKeyResponse apiKey, String rawKey) {
    public static ServiceAccountIssuedKeyResponse from(ServiceAccountIssuedKey issued) {
        return new ServiceAccountIssuedKeyResponse(ServiceAccountKeyResponse.from(issued.apiKey()), issued.rawKey());
    }
}
