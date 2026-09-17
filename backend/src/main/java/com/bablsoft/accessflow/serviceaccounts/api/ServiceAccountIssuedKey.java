package com.bablsoft.accessflow.serviceaccounts.api;

/** A freshly issued key and its plaintext, shown once (#871). */
public record ServiceAccountIssuedKey(ServiceAccountKeyView apiKey, String rawKey) {
}
