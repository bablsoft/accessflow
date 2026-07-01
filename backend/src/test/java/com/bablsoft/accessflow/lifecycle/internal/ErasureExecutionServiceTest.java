package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleDirectiveResolutionService;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErasureExecutionServiceTest {

    private static final UUID REQ = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();

    @Mock
    private DeletionRequestRepository requestRepository;
    @Mock
    private LifecycleRunRepository runRepository;
    @Mock
    private LifecycleDirectiveResolutionService directiveResolutionService;
    @Mock
    private com.bablsoft.accessflow.proxy.api.SqlParserService sqlParserService;
    @Mock
    private QueryExecutor queryExecutor;
    @Mock
    private AuditLogService auditLogService;

    private ErasureExecutionService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        var objectMapper = new tools.jackson.databind.ObjectMapper();
        service = new ErasureExecutionService(requestRepository, runRepository,
                directiveResolutionService, new ErasurePredicateCompiler(sqlParserService),
                new ErasureConditionCodec(objectMapper), queryExecutor, auditLogService,
                objectMapper, clock);
    }

    private DeletionRequestEntity request(ErasureStatus status, String scope) {
        var e = new DeletionRequestEntity();
        e.setId(REQ);
        e.setOrganizationId(ORG);
        e.setDatasourceId(DS);
        e.setSubjectType(LifecycleSubjectType.EMAIL);
        e.setSubjectIdentifier("user@example.com");
        e.setStatus(status);
        e.setScopeSnapshot(scope);
        return e;
    }

    @Test
    void execute_returnsFalseWhenNotApproved() {
        when(requestRepository.findByIdForUpdate(REQ))
                .thenReturn(Optional.of(request(ErasureStatus.PENDING_REVIEW, null)));
        assertThat(service.execute(REQ)).isFalse();
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void execute_runsGovernedDeletePerTableAndCompletes() {
        var entity = request(ErasureStatus.APPROVED,
                "{\"tables\":[\"users\",\"orders\"]}");
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(directiveResolutionService.resolveSoftDeletes(ORG, DS)).thenReturn(List.of());
        when(queryExecutor.execute(any()))
                .thenReturn(new UpdateExecutionResult(3, Duration.ZERO));

        assertThat(service.execute(REQ)).isTrue();

        var captor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor, org.mockito.Mockito.times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.queryType()).isEqualTo(QueryType.DELETE);
            assertThat(r.sql()).startsWith("DELETE FROM ");
            // Subject value is bound via a row-security predicate, never concatenated.
            assertThat(r.sql()).doesNotContain("user@example.com");
            assertThat(r.rowSecurityPredicates()).singleElement()
                    .satisfies(p -> assertThat(p.values()).containsExactly("user@example.com"));
        });
        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.EXECUTED);
        assertThat(entity.getAffectedRows()).isEqualTo(6);
        verify(runRepository).save(any(LifecycleRunEntity.class));
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void execute_marksFailedWhenATableErrors() {
        var entity = request(ErasureStatus.APPROVED, "{\"tables\":[\"users\"]}");
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(directiveResolutionService.resolveSoftDeletes(ORG, DS)).thenReturn(List.of());
        when(queryExecutor.execute(any())).thenThrow(new RuntimeException("constraint violation"));

        assertThat(service.execute(REQ)).isTrue();

        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.FAILED);
        assertThat(entity.getFailureReason()).contains("users");
    }

    @Test
    void execute_emptyScopeCompletesWithZeroRows() {
        var entity = request(ErasureStatus.APPROVED, null);
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        lenient().when(directiveResolutionService.resolveSoftDeletes(ORG, DS)).thenReturn(List.of());

        assertThat(service.execute(REQ)).isTrue();

        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.EXECUTED);
        assertThat(entity.getAffectedRows()).isZero();
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void execute_rejectsUnsafeTableIdentifier() {
        var entity = request(ErasureStatus.APPROVED, "{\"tables\":[\"users; DROP TABLE x\"]}");
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(directiveResolutionService.resolveSoftDeletes(ORG, DS)).thenReturn(List.of());

        assertThat(service.execute(REQ)).isTrue();

        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.FAILED);
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void execute_usesStructuredConditionsFromRequestConfig() {
        var entity = request(ErasureStatus.APPROVED, "{\"tables\":[\"users\"]}");
        entity.setSubjectType(null);
        entity.setSubjectIdentifier(null);
        entity.setTargetTable("users");
        entity.setConditions(
                "{\"conditions\":[{\"column\":\"status\",\"operator\":\"EQUALS\",\"values\":[\"inactive\"],\"negate\":false}]}");
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        when(directiveResolutionService.resolveSoftDeletes(ORG, DS)).thenReturn(List.of());
        when(queryExecutor.execute(any())).thenReturn(new UpdateExecutionResult(2, Duration.ZERO));

        assertThat(service.execute(REQ)).isTrue();

        var captor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).execute(captor.capture());
        var req = captor.getValue();
        assertThat(req.sql()).isEqualTo("DELETE FROM users");
        assertThat(req.rowSecurityPredicates()).singleElement().satisfies(p -> {
            assertThat(p.columnName()).isEqualTo("status");
            assertThat(p.values()).containsExactly("inactive");
        });
        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.EXECUTED);
    }

    @Test
    void findApprovedIds_delegatesToRepository() {
        when(requestRepository.findIdsByStatus(ErasureStatus.APPROVED)).thenReturn(List.of(REQ));
        assertThat(service.findApprovedIds()).containsExactly(REQ);
    }
}
