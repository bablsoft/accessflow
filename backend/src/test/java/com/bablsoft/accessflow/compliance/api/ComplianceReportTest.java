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
                UUID.randomUUID(), "a@x.com", QueryType.DELETE, "DELETE", List.of(), Instant.EPOCH));

        assertThat(report.auditTrail()).isEmpty();
    }
}
