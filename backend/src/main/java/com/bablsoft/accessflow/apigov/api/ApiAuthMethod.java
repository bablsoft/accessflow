package com.bablsoft.accessflow.apigov.api;

/**
 * How AccessFlow authenticates to a governed API connector. The secret material is stored
 * AES-256-GCM encrypted in {@code api_connectors.auth_credentials_encrypted} and never serialized.
 */
public enum ApiAuthMethod {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    BASIC,
    OAUTH2_CLIENT_CREDENTIALS,
    CUSTOM_HEADER,
    MTLS
}
