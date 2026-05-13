package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;

import java.util.UUID;

public record OAuth2ConfigDeletedEvent(
        UUID organizationId,
        OAuth2ProviderType provider) {
}
