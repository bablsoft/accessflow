package com.bablsoft.accessflow.core.api;

public enum AuthProviderType {
    LOCAL,
    SAML,
    OAUTH2,
    /** Provisioned by an identity provider over SCIM 2.0 (#621); no password, signs in via SSO. */
    SCIM
}
