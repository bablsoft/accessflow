package com.bablsoft.accessflow.apigov.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only projection of an API request for the notifications module, so it can render API-request
 * notifications without reaching into apigov internals.
 */
public interface ApiRequestNotificationLookupService {

    Optional<ApiRequestNotificationView> find(UUID apiRequestId);
}
