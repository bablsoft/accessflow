package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD + derivation preview for data-classification tags (AF-447). All methods are
 * organization-scoped: the datasource must belong to {@code organizationId}, otherwise a
 * {@link DatasourceNotFoundException} is thrown. Tags are immutable (create/delete only); a
 * column-level tag may auto-apply a derived masking policy.
 */
public interface DataClassificationAdminService {

    List<DataClassificationTagView> listForDatasource(UUID datasourceId, UUID organizationId);

    /**
     * Tags an object with each classification in the command (one persisted tag per classification)
     * and, for column-level tags with {@code applyMasking} on, idempotently derives a masking
     * policy. Returns the created tags.
     */
    List<DataClassificationTagView> create(UUID datasourceId, UUID organizationId,
                                           CreateDataClassificationTagCommand command);

    void delete(UUID tagId, UUID datasourceId, UUID organizationId);

    DataClassificationDerivationView previewDerivation(UUID datasourceId, UUID organizationId);

    /** Every classification tag in the organization, across all datasources, for reporting (#459). */
    List<OrganizationDataClassificationView> listForOrganization(UUID organizationId);
}
