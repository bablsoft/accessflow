package com.bablsoft.accessflow.apigov.api;

/**
 * How a connector's OAuth2 client credentials are presented to the token endpoint when fetching an
 * outbound access token.
 */
public enum Oauth2ClientAuth {
    /** HTTP Basic header with URL-encoded {@code client_id:client_secret} (RFC 6749 §2.3.1 default). */
    CLIENT_SECRET_BASIC,
    /** {@code client_id} and {@code client_secret} sent in the form body. */
    CLIENT_SECRET_POST
}
