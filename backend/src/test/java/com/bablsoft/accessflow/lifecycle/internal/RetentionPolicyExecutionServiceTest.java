package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleDirectiveResolutionService;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunKind;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionPolicyExecutionServiceTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID POLICY = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();

    @Mock LifecycleRunRepository runRepository;
    @Mock RetentionPolicyRepository policyRepository;
    @Mock SqlParserService sqlParserService;
    @Mock LifecycleDirectiveResolutionService directiveResolutionService;
    @Mock QueryExecutor queryExecutor;
    @Mock AuditLogService auditLogService;

    private RetentionPolicyExecutionService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        var objectMapper = new tools.jackson.databind.ObjectMapper();
        service = new RetentionPolicyExecutionService(runRepository, policyRepository,
                new ErasurePredicateCompiler(sqlParserService),
                new ErasureConditionCodec(objectMapper), directiveResolutionService, queryExecutor,
                auditLogService, objectMapper, clock);
    }

    private LifecycleRunEntity stagedRun() {
        var r = new LifecycleRunEntity();
        r.setId(RUN);
        r.setOrganizationId(ORG);
        r.setDatasourceId(DS);
        r.setKind(LifecycleRunKind.RETENTION_POLICY);
        r.setPolicyId(POLICY);
        r.setStatus(LifecycleRunStatus.STAGED);
        r.setAction(LifecycleAction.HARD_DELETE);
        return r;
    }

    private RetentionPolicyEntity policy(LifecycleAction action) {
        var p = new RetentionPolicyEntity();
        p.setId(POLICY);
        p.setOrganizationId(ORG);
        p.setDatasourceId(DS);
        p.setTargetTable("events");
        p.setTimestampColumn("created_at");
        p.setRetentionWindow("P30D");
        p.setAction(action);
        return p;
    }

    @Test
    void execute_returnsFalseWhenNotStaged() {
        var run = stagedRun();
        run.setStatus(LifecycleRunStatus.COMPLETED);
        when(runRepository.findByIdForUpdate(RUN)).thenReturn(Optional.of(run));
        assertThat(service.execute(RUN)).isFalse();
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void execute_hardDeleteRunsGovernedDeleteAndCompletes() {
        when(runRepository.findByIdForUpdate(RUN)).thenReturn(Optional.of(stagedRun()));
        when(policyRepository.findById(POLICY)).thenReturn(Optional.of(policy(LifecycleAction.HARD_DELETE)));
        when(queryExecutor.execute(any())).thenReturn(new UpdateExecutionResult(7, Duration.ZERO));

        assertThat(service.execute(RUN)).isTrue();

        var captor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).execute(captor.capture());
        var req = captor.getValue();
        assertThat(req.queryType()).isEqualTo(QueryType.DELETE);
        assertThat(req.sql()).startsWith("DELETE FROM events WHERE created_at <");
        var runCaptor = ArgumentCaptor.forClass(LifecycleRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(LifecycleRunStatus.COMPLETED);
        assertThat(runCaptor.getValue().getAffectedRows()).isEqualTo(7);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void execute_pseudonymizeIsReadTimeNoDestructiveWrite() {
        when(runRepository.findByIdForUpdate(RUN)).thenReturn(Optional.of(stagedRun()));
        when(policyRepository.findById(POLICY)).thenReturn(Optional.of(policy(LifecycleAction.PSEUDONYMIZE)));

        assertThat(service.execute(RUN)).isTrue();

        verify(queryExecutor, never()).execute(any());
        var runCaptor = ArgumentCaptor.forClass(LifecycleRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(LifecycleRunStatus.COMPLETED);
        assertThat(runCaptor.getValue().getMethod()).contains("read-time");
    }

    @Test
    void execute_marksFailedWhenPolicyGone() {
        when(runRepository.findByIdForUpdate(RUN)).thenReturn(Optional.of(stagedRun()));
        when(policyRepository.findById(POLICY)).thenReturn(Optional.empty());

        assertThat(service.execute(RUN)).isTrue();

        var runCaptor = ArgumentCaptor.forClass(LifecycleRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(LifecycleRunStatus.FAILED);
    }

    @Test
    void findStagedRunIds_delegatesToRepository() {
        when(runRepository.findIdsByStatusAndKind(LifecycleRunStatus.STAGED,
                LifecycleRunKind.RETENTION_POLICY)).thenReturn(List.of(RUN));
        assertThat(service.findStagedRunIds()).containsExactly(RUN);
    }
}
