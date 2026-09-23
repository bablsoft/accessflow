package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.UUID;

/**
 * Resolves what a drift scan compares an environment against (#881).
 *
 * <p>The two live modes re-introspect a second customer database, so every failure on that side is
 * caught here and degraded to an unresolved baseline: the scanned environment is healthy, it is the
 * reference that is not, and reporting that as a failed scan would be a lie. A failure on the
 * scanned side, by contrast, belongs to the caller and is a failed scan.
 *
 * <p>Both live modes require the baseline and the scanned datasource to run the same engine.
 * Comparing across engines would report a type mismatch on essentially every column forever, since
 * their type vocabularies differ — the same flapping failure the sampling-engine rule exists to
 * prevent. That requirement also makes a sampling baseline unreachable, since the scanned side is
 * already known deterministic by the time this runs; no separate check is needed, and none should
 * be added.
 */
@Component
@RequiredArgsConstructor
class SchemaDriftBaselineResolver {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftBaselineResolver.class);

    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final SchemaChangeSetPromotionRepository promotionRepository;
    private final ObjectMapper objectMapper;

    SchemaDriftBaselineResolution resolve(SchemaDriftScanContext ctx) {
        return switch (ctx.baseline()) {
            case PREVIOUS_ENVIRONMENT -> resolvePreviousEnvironment(ctx);
            case BASELINE_ENVIRONMENT -> resolveBaselineEnvironment(ctx);
            case PROMOTION_SNAPSHOT -> resolvePromotionSnapshot(ctx);
        };
    }

    /**
     * The adjacent lower rung <em>that binds a datasource</em>. Deploy-only rungs are skipped rather
     * than treated as a wall, exactly as the promotion ladder gate skips them (#880).
     */
    private SchemaDriftBaselineResolution resolvePreviousEnvironment(SchemaDriftScanContext ctx) {
        var scanned = environmentLookupService.findById(ctx.environmentId()).orElse(null);
        if (scanned == null) {
            return SchemaDriftBaselineResolution.unresolved(
                    SchemaDriftScanReason.BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND);
        }
        var previous = environmentLookupService.listByPipeline(ctx.pipelineId()).stream()
                .filter(env -> env.sortOrder() < scanned.sortOrder())
                .filter(env -> env.datasourceId() != null)
                .max(Comparator.comparingInt(DeploymentEnvironmentView::sortOrder))
                .orElse(null);
        if (previous == null) {
            return SchemaDriftBaselineResolution.unresolved(
                    SchemaDriftScanReason.BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND);
        }
        return introspectBaseline(ctx, previous.datasourceId());
    }

    private SchemaDriftBaselineResolution resolveBaselineEnvironment(SchemaDriftScanContext ctx) {
        if (ctx.baselineEnvironmentId() == null) {
            return SchemaDriftBaselineResolution.unresolved(
                    SchemaDriftScanReason.BASELINE_ENVIRONMENT_NOT_CONFIGURED);
        }
        // The designation is pipeline-wide, so the job reaches the designated rung itself on every
        // run. Comparing it against itself is vacuously clean, which must never read as "no drift".
        if (ctx.baselineEnvironmentId().equals(ctx.environmentId())) {
            return SchemaDriftBaselineResolution.unresolved(
                    SchemaDriftScanReason.BASELINE_ENVIRONMENT_IS_TARGET);
        }
        var baseline = environmentLookupService.findById(ctx.baselineEnvironmentId()).orElse(null);
        // A reference on another pipeline is indistinguishable from an absent one, the module's
        // 404-never-403 shape.
        if (baseline == null || !ctx.pipelineId().equals(baseline.pipelineId())) {
            return SchemaDriftBaselineResolution.unresolved(
                    SchemaDriftScanReason.BASELINE_ENVIRONMENT_NOT_FOUND);
        }
        if (baseline.datasourceId() == null) {
            return SchemaDriftBaselineResolution.unresolved(
                    SchemaDriftScanReason.BASELINE_ENVIRONMENT_NO_DATASOURCE);
        }
        return introspectBaseline(ctx, baseline.datasourceId());
    }

    private SchemaDriftBaselineResolution introspectBaseline(SchemaDriftScanContext ctx, UUID datasourceId) {
        try {
            var datasource = datasourceAdminService.getForAdmin(datasourceId, ctx.organizationId());
            if (datasource.dbType() != ctx.dbType()) {
                return SchemaDriftBaselineResolution.unresolved(
                        SchemaDriftScanReason.BASELINE_ENGINE_MISMATCH);
            }
            return SchemaDriftBaselineResolution.resolved(
                    datasourceAdminService.introspectSchemaForSystem(datasourceId, ctx.organizationId()));
        } catch (RuntimeException ex) {
            log.warn("Could not introspect the drift baseline datasource {} for environment {}: {}",
                    datasourceId, ctx.environmentId(), ex.getMessage());
            return SchemaDriftBaselineResolution.unresolved(
                    SchemaDriftScanReason.BASELINE_INTROSPECTION_FAILED);
        }
    }

    /**
     * The schema as introspected just after the newest promotion applied here — the mode that catches
     * out-of-band changes. It opens no second connection.
     */
    private SchemaDriftBaselineResolution resolvePromotionSnapshot(SchemaDriftScanContext ctx) {
        var promotion = promotionRepository
                .findFirstByOrganizationIdAndEnvironmentIdAndStatusOrderByAppliedAtDesc(
                        ctx.organizationId(), ctx.environmentId(), SchemaChangePromotionStatus.APPLIED)
                .orElse(null);
        // The newest applied promotion or nothing: an older promotion's snapshot would report the newest
        // change set's own DDL as drift. The snapshot is taken asynchronously just after the apply, and
        // may be absent for good if the target was unreachable then.
        if (promotion == null || promotion.getSchemaSnapshot() == null) {
            return SchemaDriftBaselineResolution.unresolved(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING);
        }
        // The snapshot describes the database the promotion ran against. If the environment has been
        // rebound since, it describes a different database and comparing them is meaningless.
        if (!ctx.datasourceId().equals(promotion.getDatasourceId())) {
            return SchemaDriftBaselineResolution.unresolved(SchemaDriftScanReason.BASELINE_DATASOURCE_REBOUND);
        }
        try {
            return SchemaDriftBaselineResolution.resolved(
                    objectMapper.readValue(promotion.getSchemaSnapshot(), DatabaseSchemaView.class));
        } catch (RuntimeException ex) {
            log.warn("Could not read the promotion snapshot of promotion {} for environment {}: {}",
                    promotion.getId(), ctx.environmentId(), ex.getMessage());
            return SchemaDriftBaselineResolution.unresolved(SchemaDriftScanReason.BASELINE_SNAPSHOT_UNREADABLE);
        }
    }
}
