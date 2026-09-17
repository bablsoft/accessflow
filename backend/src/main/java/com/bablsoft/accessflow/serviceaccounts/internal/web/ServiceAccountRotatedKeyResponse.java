package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRotatedKey;

/**
 * {@code rawKey} is the replacement's plaintext, shown once; {@code supersededKey} is the old key
 * with its new {@code expiresAt} — the end of the grace window — and {@code revokedAt} untouched.
 */
public record ServiceAccountRotatedKeyResponse(ServiceAccountKeyResponse apiKey, String rawKey,
                                               ServiceAccountKeyResponse supersededKey) {
    public static ServiceAccountRotatedKeyResponse from(ServiceAccountRotatedKey rotated) {
        return new ServiceAccountRotatedKeyResponse(ServiceAccountKeyResponse.from(rotated.apiKey()),
                rotated.rawKey(), ServiceAccountKeyResponse.from(rotated.supersededKey()));
    }
}
