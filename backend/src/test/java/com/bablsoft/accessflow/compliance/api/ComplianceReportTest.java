package com.bablsoft.accessflow.compliance.api;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceReportTest {

    @Test
    void nullListsBecomeEmptyAndRowCountIsZero() {
        var report = new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, UUID.randomUUID(),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null, null, false);

        assertThat(report.classifiedAccess()).isEmpty();
        assertThat(report.auditTrail()).isEmpty();
        assertThat(report.rowCount()).isZero();
    }

    @Test
    void rowCountSumsBothLists() {
        var access = List.of(new ClassifiedAccessReportRow(UUID.randomUUID(), UUID.randomUUID(),
                "ds", UUID.randomUUID(), "a@x.com", QueryType.SELECT, List.of("t"),
                List.of(), 1L, Instant.EPOCH));
        var report = new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, UUID.randomUUID(),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, access, List.of(), false);

        assertThat(report.rowCount()).isEqualTo(1);
    }

    @Test
    void copiesDefendAgainstCallerMutation() {
        var mutable = new ArrayList<RegulatoryAuditTrailRow>();
        var report = new ComplianceReport(ComplianceReportType.REGULATORY_AUDIT_TRAIL, UUID.randomUUID(),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, List.of(), mutable, false);
        mutable.add(new RegulatoryAuditTrailRow(UUID.randomUUID(), UUID.randomUUID(), "ds",
                UUID.randomUUID(), "a@x.com", QueryType.DELETE, "DELETE", List.of(), Instant.EPOCH, null));

        assertThat(report.auditTrail()).isEmpty();
    }

    @Test
    void deploymentMetricsListDefaultsToEmptyAndCountsTowardRowCount() {
        var empty = new ComplianceReport(ComplianceReportType.DEPLOYMENT_METRICS, UUID.randomUUID(),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null, null, null, false);
        assertThat(empty.deploymentMetrics()).isEmpty();
        assertThat(empty.retentionAdherence()).isEmpty();

        var row = new DeploymentMetricsReportRow(UUID.randomUUID(), "p", UUID.randomUUID(), "prod",
                1, null, null, null, null, 0, 0, 0, null, null, 0, null, null, null, 0, 0);
        var mutable = new ArrayList<DeploymentMetricsReportRow>(List.of(row));
        var report = new ComplianceReport(ComplianceReportType.DEPLOYMENT_METRICS, UUID.randomUUID(),
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null, null, null, mutable, false);
        mutable.clear();

        assertThat(report.deploymentMetrics()).containsExactly(row);
        assertThat(report.rowCount()).isEqualTo(1);
    }
}
