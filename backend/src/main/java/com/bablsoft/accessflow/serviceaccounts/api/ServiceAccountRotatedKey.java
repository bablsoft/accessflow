package com.bablsoft.accessflow.serviceaccounts.api;

/**
 * The outcome of a rotation (#871): the replacement key with its plaintext, and the superseded key
 * as it now stands — {@code expiresAt} at the end of the grace window, {@code revokedAt} untouched.
 */
public record ServiceAccountRotatedKey(ServiceAccountKeyView apiKey, String rawKey,
                                       ServiceAccountKeyView supersededKey) {
}
