package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.internal.config.DiscoveryProperties;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryStaleSweepServiceTest {

    @Mock
    private DiscoveryFindingRepository findingRepository;
    @Mock
    private AuditLogService auditLogService;

    private final UUID dsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private DiscoveryStaleSweepService newService(int threshold) {
        return new DiscoveryStaleSweepService(findingRepository, auditLogService,
                new DiscoveryProperties(null, null, null, null, null, null, null, threshold, null));
    }

    private DiscoveryFindingEntity finding(String schema, String table, int missed) {
        return finding(schema, table, missed, DiscoveryDetector.EMAIL);
    }

    private DiscoveryFindingEntity finding(String schema, String table, int missed,
                                           DiscoveryDetector detector) {
        var entity = new DiscoveryFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(dsId);
        entity.setSchemaName(schema);
        entity.setTableName(table);
        entity.setColumnName("email");
        entity.setClassification(DataClassification.PII);
        entity.setDetector(detector);
        entity.setStatus(DiscoveryFindingStatus.PENDING);
        entity.setMissedScanCount(missed);
        return entity;
    }

    private void stubPending(DiscoveryFindingEntity... findings) {
        when(findingRepository.findAllByDatasourceIdAndOrganizationIdAndStatus(dsId, orgId,
                DiscoveryFindingStatus.PENDING)).thenReturn(List.of(findings));
    }

    private static Set<DiscoveryTableKey> tables(String schema, String table) {
        return Set.of(DiscoveryTableKey.of(schema, table));
    }

    @Test
    @DisplayName("counts a miss without retiring the finding below the threshold")
    void incrementsBelowThreshold() {
        var pending = finding("public", "users", 0);
        stubPending(pending);

        var result = newService(3).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(pending.getMissedScanCount()).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(result.aged()).isEqualTo(1);
        assertThat(result.expired()).isZero();
        verify(findingRepository).save(pending);
        verify(auditLogService, never()).record(any());
    }

    @Test
    @DisplayName("retires the finding as STALE on the Nth consecutive miss, not the N+1th")
    void expiresExactlyAtTheThreshold() {
        var pending = finding("public", "users", 2);
        stubPending(pending);

        var result = newService(3).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(pending.getMissedScanCount()).isEqualTo(3);
        assertThat(pending.getStatus()).isEqualTo(DiscoveryFindingStatus.STALE);
        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.expiredAuditTruncated()).isFalse();
    }

    @Test
    @DisplayName("a threshold of 1 retires on the first miss")
    void thresholdOfOneExpiresImmediately() {
        var pending = finding("public", "users", 0);
        stubPending(pending);

        newService(1).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(pending.getStatus()).isEqualTo(DiscoveryFindingStatus.STALE);
    }

    @Test
    @DisplayName("leaves findings for tables this run did not sample")
    void ignoresUnscannedTables() {
        var pending = finding("public", "orders", 2);
        stubPending(pending);

        var result = newService(3).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(pending.getMissedScanCount()).isEqualTo(2);
        assertThat(pending.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(result.aged()).isZero();
        verify(findingRepository, never()).save(any());
    }

    @Test
    @DisplayName("leaves findings this run re-proposed")
    void ignoresSeenFindings() {
        var pending = finding("public", "users", 1);
        stubPending(pending);

        var result = newService(3).sweep(dsId, orgId, tables("public", "users"), Set.of(),
                Set.of(pending.getId()));

        assertThat(pending.getMissedScanCount()).isEqualTo(1);
        assertThat(result.aged()).isZero();
    }

    @Test
    @DisplayName("matches a null schema against the empty-string key, like COALESCE(schema_name,'')")
    void matchesNullSchema() {
        var pending = finding(null, "users", 0);
        stubPending(pending);

        var result = newService(3).sweep(dsId, orgId, tables(null, "users"), Set.of(), Set.of());

        assertThat(result.aged()).isEqualTo(1);
    }

    @Test
    @DisplayName("never ages an AI finding for a table the capped AI pass did not reach")
    void ignoresAiFindingsWhereTheAiPassDidNotRun() {
        var pending = finding("public", "users", 2, DiscoveryDetector.AI);
        stubPending(pending);

        var result = newService(3).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(pending.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(result.aged()).isZero();
    }

    @Test
    @DisplayName("ages an AI finding when the AI pass did run for its table")
    void agesAiFindingsWhereTheAiPassRan() {
        var pending = finding("public", "users", 0, DiscoveryDetector.AI);
        stubPending(pending);

        var result = newService(3).sweep(dsId, orgId, tables("public", "users"),
                tables("public", "users"), Set.of());

        assertThat(result.aged()).isEqualTo(1);
    }

    @Test
    @DisplayName("writes one expiry audit row per retired finding")
    void auditsEachExpiry() {
        var pending = finding("public", "users", 0);
        stubPending(pending);

        newService(1).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DISCOVERY_FINDING_EXPIRED);
        assertThat(entry.resourceId()).isEqualTo(pending.getId());
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata())
                .containsEntry("tableName", "public.users")
                .containsEntry("columnName", "email")
                .containsEntry("classification", "PII")
                .containsEntry("detector", "EMAIL")
                .containsEntry("missedScanCount", 1);
    }

    @Test
    @DisplayName("caps expiry audit rows and reports the truncation")
    void capsExpiryAuditRows() {
        var findings = new ArrayList<DiscoveryFindingEntity>();
        for (var i = 0; i < 105; i++) {
            findings.add(finding("public", "users", 0));
        }
        when(findingRepository.findAllByDatasourceIdAndOrganizationIdAndStatus(dsId, orgId,
                DiscoveryFindingStatus.PENDING)).thenReturn(findings);

        var result = newService(1).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(result.expired()).isEqualTo(105);
        assertThat(result.expiredAuditTruncated()).isTrue();
        verify(auditLogService, times(100)).record(any());
    }

    @Test
    @DisplayName("skips a row that lost the optimistic lock and keeps sweeping")
    void skipsContendedRow() {
        var contended = finding("public", "users", 0);
        var healthy = finding("public", "users", 0);
        stubPending(contended, healthy);
        doThrow(new OptimisticLockingFailureException("decided concurrently"))
                .when(findingRepository).save(contended);

        var result = newService(3).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(result.aged()).isEqualTo(1);
        verify(findingRepository).save(healthy);
    }

    @Test
    @DisplayName("an audit failure never fails the sweep")
    void survivesAuditFailure() {
        var pending = finding("public", "users", 0);
        stubPending(pending);
        doThrow(new IllegalStateException("audit down")).when(auditLogService).record(any());

        var result = newService(1).sweep(dsId, orgId, tables("public", "users"), Set.of(), Set.of());

        assertThat(result.expired()).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(DiscoveryFindingStatus.STALE);
    }

    @Test
    @DisplayName("does nothing when the run sampled no tables at all")
    void noOpWhenNothingWasScanned() {
        var result = newService(3).sweep(dsId, orgId, Set.of(), Set.of(), Set.of());

        assertThat(result).isEqualTo(DiscoveryStaleSweepService.SweepResult.NONE);
        verify(findingRepository, never()).findAllByDatasourceIdAndOrganizationIdAndStatus(any(),
                any(), any());
    }
}
