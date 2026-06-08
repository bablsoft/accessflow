package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.DatasourceSpec;
import com.bablsoft.accessflow.core.api.CreateDatasourceCommand;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UpdateDatasourceCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatasourceReconciler {

    private static final int LIST_PAGE_SIZE = 500;

    private final DatasourceAdminService datasourceAdminService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public void reconcile(UUID organizationId,
                          List<DatasourceSpec> specs,
                          Map<String, UUID> reviewPlansByName,
                          Map<String, UUID> aiConfigsByName) {
        for (var spec : specs) {
            applyOne(organizationId, spec, reviewPlansByName, aiConfigsByName);
        }
    }

    private void applyOne(UUID organizationId,
                          DatasourceSpec spec,
                          Map<String, UUID> reviewPlansByName,
                          Map<String, UUID> aiConfigsByName) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalStateException("Datasource spec is missing 'name'");
        }
        if (spec.dbType() == null) {
            throw new IllegalStateException("Datasource '%s' is missing 'dbType'".formatted(spec.name()));
        }
        if (spec.dbType() == DbType.CUSTOM) {
            throw new IllegalStateException(
                    ("Datasource '%s' has dbType=CUSTOM; CUSTOM driver bootstrap is not supported."
                            + " Upload the JAR via the admin API and reference the resulting datasource.")
                            .formatted(spec.name()));
        }

        var reviewPlanId = resolveReviewPlanId(spec, reviewPlansByName);
        var aiConfigId = resolveAiConfigId(spec, aiConfigsByName);

        var specMap = specFields(spec, reviewPlanId, aiConfigId);
        var specFingerprint = fingerprinter.fingerprint(specMap);

        var existing = findByName(organizationId, spec.name());
        if (existing.isEmpty()) {
            var created = datasourceAdminService.create(new CreateDatasourceCommand(
                    organizationId,
                    spec.name(),
                    spec.dbType(),
                    spec.host(),
                    spec.port(),
                    spec.databaseName(),
                    spec.username(),
                    spec.password(),
                    spec.sslMode(),
                    spec.connectionPoolSize(),
                    spec.maxRowsPerQuery(),
                    spec.requireReviewReads(),
                    spec.requireReviewWrites(),
                    reviewPlanId,
                    spec.aiAnalysisEnabled(),
                    aiConfigId,
                    spec.textToSqlEnabled(),
                    null,
                    null,
                    spec.jdbcUrlOverride(),
                    null,
                    null,
                    null));
            log.info("Bootstrap: created datasource '{}' (id={})", spec.name(), created.id());
            stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.DATASOURCE,
                    created.id(), specFingerprint,
                    new BootstrapResourceUpsertedEvent(
                            organizationId,
                            BootstrapResourceType.DATASOURCE,
                            created.id(),
                            BootstrapChangeKind.CREATE,
                            List.of(),
                            Map.of("name", created.name(), "db_type", created.dbType().name())));
            return;
        }

        var view = existing.get();
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.DATASOURCE, view.id())
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: datasource '{}' unchanged, skipping update", spec.name());
            return;
        }

        var viewMap = viewFields(view);
        var updated = datasourceAdminService.update(view.id(), organizationId,
                new UpdateDatasourceCommand(
                        spec.name(),
                        spec.host(),
                        spec.port(),
                        spec.databaseName(),
                        spec.username(),
                        spec.password(),
                        spec.sslMode(),
                        spec.connectionPoolSize(),
                        spec.maxRowsPerQuery(),
                        spec.requireReviewReads(),
                        spec.requireReviewWrites(),
                        reviewPlanId,
                        spec.aiAnalysisEnabled(),
                        aiConfigId,
                        spec.textToSqlEnabled(),
                        null,
                        spec.jdbcUrlOverride(),
                        null,
                        null,
                        null,
                        Boolean.TRUE));
        log.info("Bootstrap: updated datasource '{}' (id={})", spec.name(), updated.id());
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.DATASOURCE,
                updated.id(), specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.DATASOURCE,
                        updated.id(),
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(viewMap, specMap),
                        Map.of("name", updated.name())));
    }

    private UUID resolveReviewPlanId(DatasourceSpec spec, Map<String, UUID> reviewPlansByName) {
        if (spec.reviewPlanName() == null || spec.reviewPlanName().isBlank()) {
            return null;
        }
        var id = reviewPlansByName.get(spec.reviewPlanName());
        if (id == null) {
            throw new IllegalStateException(
                    "Datasource '%s' references unknown review plan '%s'"
                            .formatted(spec.name(), spec.reviewPlanName()));
        }
        return id;
    }

    private UUID resolveAiConfigId(DatasourceSpec spec, Map<String, UUID> aiConfigsByName) {
        if (spec.aiConfigName() == null || spec.aiConfigName().isBlank()) {
            return null;
        }
        var id = aiConfigsByName.get(spec.aiConfigName());
        if (id == null) {
            throw new IllegalStateException(
                    "Datasource '%s' references unknown AI config '%s'"
                            .formatted(spec.name(), spec.aiConfigName()));
        }
        return id;
    }

    private Optional<DatasourceView> findByName(UUID organizationId, String name) {
        var page = datasourceAdminService.listForAdmin(organizationId, PageRequest.of(0, LIST_PAGE_SIZE));
        return page.content().stream()
                .filter(view -> view.name().equalsIgnoreCase(name))
                .findFirst();
    }

    private static Map<String, Object> specFields(DatasourceSpec spec, UUID reviewPlanId, UUID aiConfigId) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", spec.name());
        map.put("db_type", spec.dbType().name());
        map.put("host", spec.host());
        map.put("port", spec.port());
        map.put("database_name", spec.databaseName());
        map.put("username", spec.username());
        map.put("password", spec.password());
        map.put("ssl_mode", spec.sslMode() == null ? null : spec.sslMode().name());
        map.put("connection_pool_size", spec.connectionPoolSize());
        map.put("max_rows_per_query", spec.maxRowsPerQuery());
        map.put("require_review_reads", spec.requireReviewReads());
        map.put("require_review_writes", spec.requireReviewWrites());
        map.put("review_plan_id", reviewPlanId == null ? null : reviewPlanId.toString());
        map.put("ai_analysis_enabled", spec.aiAnalysisEnabled());
        map.put("ai_config_id", aiConfigId == null ? null : aiConfigId.toString());
        map.put("text_to_sql_enabled", spec.textToSqlEnabled());
        map.put("jdbc_url_override", spec.jdbcUrlOverride());
        return map;
    }

    private static Map<String, Object> viewFields(DatasourceView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", view.name());
        map.put("db_type", view.dbType().name());
        map.put("host", view.host());
        map.put("port", view.port());
        map.put("database_name", view.databaseName());
        map.put("username", view.username());
        map.put("ssl_mode", view.sslMode() == null ? null : view.sslMode().name());
        map.put("connection_pool_size", view.connectionPoolSize());
        map.put("max_rows_per_query", view.maxRowsPerQuery());
        map.put("require_review_reads", view.requireReviewReads());
        map.put("require_review_writes", view.requireReviewWrites());
        map.put("review_plan_id", view.reviewPlanId() == null ? null : view.reviewPlanId().toString());
        map.put("ai_analysis_enabled", view.aiAnalysisEnabled());
        map.put("ai_config_id", view.aiConfigId() == null ? null : view.aiConfigId().toString());
        map.put("text_to_sql_enabled", view.textToSqlEnabled());
        map.put("jdbc_url_override", view.jdbcUrlOverride());
        return map;
    }
}
