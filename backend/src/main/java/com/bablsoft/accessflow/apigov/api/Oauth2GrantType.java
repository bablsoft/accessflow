package com.bablsoft.accessflow.apigov.api;

/**
 * The OAuth2 grant AccessFlow runs against a connector's token endpoint to obtain an outbound access
 * token. Applies only when the connector's {@link ApiAuthMethod} is
 * {@code OAUTH2_CLIENT_CREDENTIALS}.
 */
public enum Oauth2GrantType {
    /** Machine-to-machine: {@code grant_type=client_credentials}. */
    CLIENT_CREDENTIALS,
    /** Exchange a stored long-lived refresh token: {@code grant_type=refresh_token}. */
    REFRESH_TOKEN,
    /** Resource-owner password credentials: {@code grant_type=password}. */
    PASSWORD
}
