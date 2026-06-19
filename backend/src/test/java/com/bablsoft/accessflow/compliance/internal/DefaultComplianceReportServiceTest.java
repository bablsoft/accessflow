package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ComplianceReportRequest;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.compliance.api.InvalidReportPeriodException;
import com.bablsoft.accessflow.compliance.internal.config.ComplianceProperties;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.OrganizationDataClassificationView;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultComplianceReportServiceTest {

    @Mock QuerySnapshotService snapshotService;
    @Mock DataClassificationAdminService dataClassificationAdminService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock UserAdminService userAdminService;
    @Mock ReviewDecisionsParser reviewDecisionsParser;

    private DefaultComplianceReportService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID dsId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-04-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-07-01T00:00:00Z");
    private final Instant generatedAt = Instant.parse("2026-07-02T09:00:00Z");

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(generatedAt, ZoneOffset.UTC);
        var properties = new ComplianceProperties(Duration.ofDays(366), 2);
        service = new DefaultComplianceReportService(snapshotService, dataClassificationAdminService,
                datasourceAdminService, userAdminService, reviewDecisionsParser, properties, clock);
        lenient().when(datasourceAdminService.listForAdmin(eq(orgId), any()))
                .thenReturn(PageResponse.empty(0, 1000));
        lenient().when(userAdminService.findByIds(eq(orgId), any()))
                .thenReturn(Map.of(userId, user()));
    }

    private UserView user() {
        return new UserView(userId, "a@x.com", "Alice", com.bablsoft.accessflow.core.api.UserRoleType.ANALYST,
                orgId, true, com.bablsoft.accessflow.core.api.AuthProviderType.LOCAL, null, null, null,
                false, generatedAt);
    }

    private QuerySnapshotView snapshot(QueryType type, List<String> tables) {
        return new QuerySnapshotView(UUID.randomUUID(), UUID.randomUUID(), orgId, dsId, userId,
                "SQL", type, false, DbType.POSTGRESQL, tables, null, null, "[]", 1L, 2, from, from);
    }

    private OrganizationDataClassificationView tag(String table, DataClassification classification) {
        return new OrganizationDataClassificationView(UUID.randomUUID(), dsId, "Prod", table, null,
                classification, null, from, from);
    }

    @Test
    void classifiedAccessJoinsSnapshotsToClassifications() {
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(snapshot(QueryType.SELECT, List.of("customers"))));
        when(dataClassificationAdminService.listForOrganization(orgId))
                .thenReturn(List.of(tag("customers", DataClassification.PII)));

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to, null));

        assertThat(report.type()).isEqualTo(ComplianceReportType.CLASSIFIED_ACCESS);
        assertThat(report.generatedAt()).isEqualTo(generatedAt);
        assertThat(report.classifiedAccess()).hasSize(1);
        assertThat(report.classifiedAccess().getFirst().submitterEmail()).isEqualTo("a@x.com");
        assertThat(report.auditTrail()).isEmpty();
        assertThat(report.truncated()).isFalse();
    }

    @Test
    void classifiedAccessFlagsTruncationAndTrimsToCap() {
        // cap = 2; return cap+1 = 3 to trigger truncation.
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        snapshot(QueryType.SELECT, List.of("customers")),
                        snapshot(QueryType.SELECT, List.of("customers")),
                        snapshot(QueryType.SELECT, List.of("customers"))));
        when(dataClassificationAdminService.listForOrganization(orgId))
                .thenReturn(List.of(tag("customers", DataClassification.PII)));

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to, null));

        assertThat(report.truncated()).isTrue();
        assertThat(report.classifiedAccess()).hasSize(2);
    }

    @Test
    void regulatoryTrailFiltersToDdlAndDeleteAndUsesApprovers() {
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), isNull(),
                eq(Set.of(QueryType.DDL, QueryType.DELETE)), eq(3)))
                .thenReturn(List.of(snapshot(QueryType.DELETE, List.of("customers"))));
        when(reviewDecisionsParser.approvers(any()))
                .thenReturn(List.of(new Approver("rev@x.com", "Rev", "APPROVED", from)));

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.REGULATORY_AUDIT_TRAIL, from, to, null));

        assertThat(report.type()).isEqualTo(ComplianceReportType.REGULATORY_AUDIT_TRAIL);
        assertThat(report.auditTrail()).hasSize(1);
        assertThat(report.auditTrail().getFirst().approvers()).hasSize(1);
        assertThat(report.auditTrail().getFirst().approvers().getFirst().email()).isEqualTo("rev@x.com");
        assertThat(report.classifiedAccess()).isEmpty();
    }

    @Test
    void scopesToDatasourceWhenRequested() {
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), eq(dsId), isNull(), eq(3)))
                .thenReturn(List.of());
        when(dataClassificationAdminService.listForOrganization(orgId)).thenReturn(List.of());

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to, dsId));

        assertThat(report.datasourceId()).isEqualTo(dsId);
        verify(snapshotService).findForPeriod(eq(orgId), eq(from), eq(to), eq(dsId), isNull(), eq(3));
    }

    @Test
    void rejectsInvertedPeriod() {
        assertThatThrownBy(() -> service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, to, from, null)))
                .isInstanceOf(InvalidReportPeriodException.class);
    }

    @Test
    void rejectsMissingPeriodBound() {
        assertThatThrownBy(() -> service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, null, to, null)))
                .isInstanceOf(InvalidReportPeriodException.class);
    }

    @Test
    void rejectsPeriodLongerThanMax() {
        var tooLong = from.plus(Duration.ofDays(400));
        assertThatThrownBy(() -> service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, tooLong, null)))
                .isInstanceOf(InvalidReportPeriodException.class);
    }
}
