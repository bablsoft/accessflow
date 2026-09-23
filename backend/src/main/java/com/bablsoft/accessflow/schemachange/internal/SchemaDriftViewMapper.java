package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingView;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanView;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;

import java.util.UUID;

/** Entity to {@code api/} view conversion for the drift surface (#881). */
final class SchemaDriftViewMapper {

    /** Mirrors the V180 column defaults, for a pipeline that has never been configured. */
    static final int DEFAULT_SCAN_INTERVAL_HOURS = 24;

    private SchemaDriftViewMapper() {
    }

    static SchemaDriftScanView toView(SchemaDriftScanEntity entity) {
        return new SchemaDriftScanView(entity.getId(), entity.getOrganizationId(), entity.getPipelineId(),
                entity.getEnvironmentId(), entity.getDatasourceId(), entity.getBaseline(),
                entity.getStartedAt(), entity.getFinishedAt(), entity.isApplicable(),
                entity.getFindingsCount(), entity.isPartial(), entity.getErrorMessage());
    }

    static SchemaDriftFindingView toView(SchemaDriftFindingEntity entity) {
        return new SchemaDriftFindingView(entity.getId(), entity.getOrganizationId(),
                entity.getScan().getId(), entity.getEnvironmentId(), entity.getObjectPath(),
                entity.getFindingKind(), entity.getExpectedValue(), entity.getActualValue(),
                entity.getStatus(), entity.getFirstDetectedAt(), entity.getLastSeenAt(),
                entity.getResolvedAt());
    }

    static SchemaDriftConfigView toView(SchemaDriftConfigEntity entity) {
        return new SchemaDriftConfigView(entity.getId(), entity.getOrganizationId(), entity.getPipelineId(),
                entity.isEnabled(), entity.getBaseline(), entity.getBaselineEnvironmentId(),
                entity.getScanIntervalHours(), entity.getLastScanAt(), entity.getLastScanError());
    }

    /**
     * The view of a pipeline that has no configuration row. A null id says "never configured", which
     * means exactly what {@code enabled = false} means: drift is off here.
     */
    static SchemaDriftConfigView disabledDefaults(UUID organizationId, UUID pipelineId) {
        return new SchemaDriftConfigView(null, organizationId, pipelineId, false,
                SchemaDriftBaseline.PREVIOUS_ENVIRONMENT, null, DEFAULT_SCAN_INTERVAL_HOURS, null, null);
    }
}
