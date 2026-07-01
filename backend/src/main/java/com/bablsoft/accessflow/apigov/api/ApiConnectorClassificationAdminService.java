package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD + derivation preview for API-connector data-classification tags (AF-518). All methods
 * are organization-scoped: the connector must belong to {@code organizationId}, otherwise an
 * {@link ApiConnectorNotFoundException} is thrown. Tags are immutable (create/delete only); a tag
 * may auto-apply a derived masking policy.
 */
public interface ApiConnectorClassificationAdminService {

    List<ApiConnectorClassificationTagView> listForConnector(UUID connectorId, UUID organizationId);

    /**
     * Tags a field with each classification in the command (one persisted tag per classification)
     * and, for tags with {@code applyMasking} on, idempotently derives a masking policy. Returns the
     * created tags.
     */
    List<ApiConnectorClassificationTagView> create(UUID connectorId, UUID organizationId,
                                                   CreateApiConnectorClassificationTagCommand command);

    void delete(UUID tagId, UUID connectorId, UUID organizationId);

    ApiConnectorClassificationDerivationView previewDerivation(UUID connectorId, UUID organizationId);
}
