package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one pipeline's drift configuration into the per-environment scans the job runs (#881), and
 * does the pipeline-level bookkeeping once every environment has been visited.
 *
 * <p>The configuration is per pipeline but scans are per environment, so {@code last_scan_at} and
 * {@code last_scan_error} are stamped here, once, rather than by each scan: a per-scan stamp would
 * let one environment's scan restart every sibling's interval and overwrite a sibling's failure with
 * its own success. A pipeline with an environment another replica was holding is not stamped at all,
 * so it stays due and the next tick retries the lot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaDriftScanCoordinator {

    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final SchemaDriftScanService scanService;
    private final SchemaDriftScanStore scanStore;

    /**
     * Scans every schema-bound environment of the configured pipeline.
     *
     * @return how many environments were actually scanned; environments held by another replica,
     *         bound to nothing, or bound to a missing datasource are not counted
     */
    public int scanPipeline(SchemaDriftConfigEntity config) {
        var scanned = 0;
        var skipped = false;
        var failures = new ArrayList<String>();
        for (var environment : environmentLookupService.listByPipeline(config.getPipelineId())) {
            if (environment.datasourceId() == null) {
                continue;
            }
            try {
                var run = scanEnvironment(config, environment);
                if (!run.ran()) {
                    skipped = true;
                    // Not an error: another replica holds this environment's lock.
                    log.info("Skipping schema drift scan for environment {} — already running on another "
                            + "replica", environment.id());
                    continue;
                }
                scanned++;
                if (run.failure() != null) {
                    failures.add(environment.name() + ": " + run.failure());
                }
            } catch (RuntimeException ex) {
                // One bad environment must never abort the rest of the pipeline's ladder.
                log.error("Schema drift scan failed for environment {}", environment.id(), ex);
                failures.add(environment.name() + ": " + SchemaDriftScanReason.SCAN_FAILED);
            }
        }
        if (!skipped) {
            scanStore.stampConfig(config.getPipelineId(), config.getOrganizationId(), summarize(failures));
        }
        return scanned;
    }

    private SchemaDriftScanRun scanEnvironment(SchemaDriftConfigEntity config, DeploymentEnvironmentView environment) {
        var datasource = datasourceAdminService.getForAdmin(environment.datasourceId(), config.getOrganizationId());
        return scanService.scan(new SchemaDriftScanContext(config.getOrganizationId(), config.getPipelineId(),
                environment.id(), datasource.id(), datasource.dbType(), config.getBaseline(),
                config.getBaselineEnvironmentId()));
    }

    /** Every failing environment, not just the last one to finish — a later success must not hide it. */
    private static String summarize(List<String> failures) {
        return failures.isEmpty() ? null : SchemaDriftScanService.truncate(String.join("; ", failures));
    }
}
