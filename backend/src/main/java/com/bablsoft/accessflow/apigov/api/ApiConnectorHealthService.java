package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/**
 * Scheduled connector health (#894). {@link #probe} runs one scheduled probe for one connector:
 * it records a history row and moves the consecutive-failure streak and {@code last_probe_status}.
 * The admin "test connection" action is deliberately separate and never touches either.
 */
public interface ApiConnectorHealthService {

    /** One scheduled probe of one connector; a connector deleted meanwhile is skipped. */
    void probe(UUID connectorId);

    /**
     * The connector's current health and its newest probe history rows.
     *
     * @throws ApiConnectorNotFoundException when the connector is not in the organization
     */
    ApiConnectorHealthView health(UUID connectorId, UUID organizationId);
}
