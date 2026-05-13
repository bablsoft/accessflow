package com.bablsoft.accessflow.security.api;

import java.util.UUID;

public class OAuth2ConfigNotFoundException extends RuntimeException {

    private final UUID organizationId;
    private final OAuth2ProviderType provider;

    public OAuth2ConfigNotFoundException(UUID organizationId, OAuth2ProviderType provider) {
        super("No oauth2_config for org " + organizationId + " provider " + provider);
        this.organizationId = organizationId;
        this.provider = provider;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public OAuth2ProviderType provider() {
        return provider;
    }
}
