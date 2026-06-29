package com.bablsoft.accessflow.apigov.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only projection of an API connector for the notifications module, so it can render
 * connector-scoped notifications (e.g. repeated OAuth2 token-fetch failure) without reaching into
 * apigov internals.
 */
public interface ApiConnectorNotificationLookupService {

    Optional<ApiConnectorNotificationView> find(UUID connectorId);
}
