package com.bablsoft.accessflow.bootstrap.internal.reconcile;

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

        var existing = findByName(organizationId, spec.name());
        if (existing.isPresent()) {
            var view = existing.get();
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
                            null,
                            spec.jdbcUrlOverride(),
                            Boolean.TRUE));
            log.info("Bootstrap: updated datasource '{}' (id={})", spec.name(), updated.id());
            return;
        }

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
                null,
                spec.jdbcUrlOverride()));
        log.info("Bootstrap: created datasource '{}' (id={})", spec.name(), created.id());
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
}
