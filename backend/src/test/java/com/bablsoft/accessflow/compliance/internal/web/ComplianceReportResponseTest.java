package com.bablsoft.accessflow.compliance.internal.web;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ClassifiedAccessReportRow;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.compliance.api.MatchedClassification;
import com.bablsoft.accessflow.compliance.api.RegulatoryAuditTrailRow;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceReportResponseTest {

    private final Instant t = Instant.parse("2026-05-01T10:00:00Z");

    @Test
    void mapsClassifiedAccessReport() {
        var report = new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, UUID.randomUUID(),
                t, t, t, UUID.randomUUID(),
                List.of(new ClassifiedAccessReportRow(UUID.randomUUID(), UUID.randomUUID(), "Prod",
                        UUID.randomUUID(), "a@x.com", QueryType.SELECT, List.of("customers"),
                        List.of(new MatchedClassification("customers", "ssn", DataClassification.PII)),
                        3L, t)),
                List.of(), false);

        var response = ComplianceReportResponse.from(report);

        assertThat(response.type()).isEqualTo(ComplianceReportType.CLASSIFIED_ACCESS);
        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(response.classifiedAccess()).hasSize(1);
        assertThat(response.classifiedAccess().getFirst().matched().getFirst().classification())
                .isEqualTo(DataClassification.PII);
        assertThat(response.auditTrail()).isEmpty();
    }

    @Test
    void mapsRegulatoryAuditTrailReport() {
        var report = new ComplianceReport(ComplianceReportType.REGULATORY_AUDIT_TRAIL, UUID.randomUUID(),
                t, t, t, null, List.of(),
                List.of(new RegulatoryAuditTrailRow(UUID.randomUUID(), UUID.randomUUID(), "Prod",
                        UUID.randomUUID(), "a@x.com", QueryType.DELETE, "DELETE FROM t",
                        List.of(new Approver("rev@x.com", "Rev", "APPROVED", t)), t)),
                true);

        var response = ComplianceReportResponse.from(report);

        assertThat(response.truncated()).isTrue();
        assertThat(response.auditTrail()).hasSize(1);
        assertThat(response.auditTrail().getFirst().approvers().getFirst().displayName())
                .isEqualTo("Rev");
        assertThat(response.classifiedAccess()).isEmpty();
    }
}
