package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeploymentVersionTrackerServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-08-20T09:00:00Z");

    private DeploymentEnvironmentVersionRepository repository;
    private DefaultDeploymentVersionTrackerService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeploymentEnvironmentVersionRepository.class);
        service = new DefaultDeploymentVersionTrackerService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstExecutionCreatesTheRow() {
        var request = request("1.0.0");
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.empty());

        service.recordExecution(request);

        var captor = ArgumentCaptor.forClass(DeploymentEnvironmentVersionEntity.class);
        verify(repository).save(captor.capture());
        var row = captor.getValue();
        assertThat(row.getId()).isNotNull();
        assertThat(row.getOrganizationId()).isEqualTo(request.getOrganizationId());
        assertThat(row.getPipelineId()).isEqualTo(request.getPipelineId());
        assertThat(row.getEnvironmentId()).isEqualTo(request.getEnvironmentId());
        assertThat(row.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(row.getCurrentRequestId()).isEqualTo(request.getId());
        assertThat(row.getDeployedAt()).isEqualTo(NOW);
        assertThat(row.getPreviousVersion()).isNull();
        assertThat(row.getPreviousRequestId()).isNull();
        assertThat(row.getPreviousDeployedAt()).isNull();
        assertThat(row.getLastOutcome()).isNull();
    }

    @Test
    void promotionShiftsCurrentToPrevious() {
        var previousRequestId = UUID.randomUUID();
        var request = request("2.0.0");
        var row = row(request, "1.0.0", previousRequestId);
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.of(row));

        service.recordExecution(request);

        verify(repository).save(row);
        assertThat(row.getPreviousVersion()).isEqualTo("1.0.0");
        assertThat(row.getPreviousRequestId()).isEqualTo(previousRequestId);
        assertThat(row.getPreviousDeployedAt()).isEqualTo(EARLIER);
        assertThat(row.getCurrentVersion()).isEqualTo("2.0.0");
        assertThat(row.getCurrentRequestId()).isEqualTo(request.getId());
        assertThat(row.getDeployedAt()).isEqualTo(NOW);
    }

    @Test
    void executionClearsAStaleLastOutcome() {
        var request = request("2.0.0");
        var row = row(request, "1.0.0", UUID.randomUUID());
        row.setLastOutcome(DeploymentOutcome.SUCCEEDED);
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.of(row));

        service.recordExecution(request);

        assertThat(row.getLastOutcome()).isNull();
    }

    @Test
    void succeededRecordsTheOutcomeOnly() {
        var request = request("1.0.0");
        var row = currentRow(request, "0.9.0");
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.of(row));

        service.recordOutcome(request, DeploymentOutcome.SUCCEEDED);

        verify(repository).save(row);
        assertThat(row.getLastOutcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        assertThat(row.getCurrentVersion()).isEqualTo("1.0.0");
        assertThat(row.getCurrentRequestId()).isEqualTo(request.getId());
        assertThat(row.getPreviousVersion()).isEqualTo("0.9.0");
    }

    @Test
    void failedRevertsToThePreviousDeploy() {
        var request = request("1.0.0");
        var row = currentRow(request, "0.9.0");
        var previousRequestId = row.getPreviousRequestId();
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.of(row));

        service.recordOutcome(request, DeploymentOutcome.FAILED);

        verify(repository).save(row);
        assertThat(row.getCurrentVersion()).isEqualTo("0.9.0");
        assertThat(row.getCurrentRequestId()).isEqualTo(previousRequestId);
        assertThat(row.getDeployedAt()).isEqualTo(EARLIER);
        assertThat(row.getPreviousVersion()).isNull();
        assertThat(row.getPreviousRequestId()).isNull();
        assertThat(row.getPreviousDeployedAt()).isNull();
        assertThat(row.getLastOutcome()).isEqualTo(DeploymentOutcome.FAILED);
    }

    @Test
    void rolledBackRevertsToThePreviousDeploy() {
        var request = request("1.0.0");
        var row = currentRow(request, "0.9.0");
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.of(row));

        service.recordOutcome(request, DeploymentOutcome.ROLLED_BACK);

        assertThat(row.getCurrentVersion()).isEqualTo("0.9.0");
        assertThat(row.getPreviousVersion()).isNull();
        assertThat(row.getLastOutcome()).isEqualTo(DeploymentOutcome.ROLLED_BACK);
    }

    @Test
    void secondConsecutiveRollbackLeavesNoCurrentVersion() {
        // The environment reverted once already, so previous_* is null and the older request is
        // current. Rolling that back too must honestly say "unknown — see history".
        var request = request("0.9.0");
        var row = currentRow(request, null);
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.of(row));

        service.recordOutcome(request, DeploymentOutcome.ROLLED_BACK);

        verify(repository).save(row);
        assertThat(row.getCurrentVersion()).isNull();
        assertThat(row.getCurrentRequestId()).isNull();
        assertThat(row.getDeployedAt()).isNull();
        assertThat(row.getLastOutcome()).isEqualTo(DeploymentOutcome.ROLLED_BACK);
    }

    @Test
    void outcomeForANonCurrentRequestIsANoOp() {
        var request = request("1.0.0");
        var row = currentRow(request, "0.9.0");
        row.setCurrentRequestId(UUID.randomUUID());
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.of(row));

        service.recordOutcome(request, DeploymentOutcome.FAILED);

        verify(repository, never()).save(any());
        assertThat(row.getLastOutcome()).isNull();
    }

    @Test
    void outcomeWithNoRowIsANoOp() {
        var request = request("1.0.0");
        when(repository.findByEnvironmentId(request.getEnvironmentId()))
                .thenReturn(Optional.empty());

        service.recordOutcome(request, DeploymentOutcome.ROLLED_BACK);

        verify(repository, never()).save(any());
    }

    private static DeploymentRequestEntity request(String version) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setPipelineId(UUID.randomUUID());
        entity.setEnvironmentId(UUID.randomUUID());
        entity.setSubmittedBy(UUID.randomUUID());
        entity.setVersion(version);
        entity.setStatus(QueryStatus.APPROVED);
        return entity;
    }

    /** A row whose current deploy is some older request, with {@code deployedAt = EARLIER}. */
    private static DeploymentEnvironmentVersionEntity row(DeploymentRequestEntity request,
                                                          String currentVersion,
                                                          UUID currentRequestId) {
        var row = new DeploymentEnvironmentVersionEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(request.getOrganizationId());
        row.setPipelineId(request.getPipelineId());
        row.setEnvironmentId(request.getEnvironmentId());
        row.setCurrentVersion(currentVersion);
        row.setCurrentRequestId(currentRequestId);
        row.setDeployedAt(EARLIER);
        return row;
    }

    /**
     * A row whose current deploy is {@code request} itself; {@code previousVersion} null means
     * the environment has no previous deploy (post-revert state).
     */
    private static DeploymentEnvironmentVersionEntity currentRow(DeploymentRequestEntity request,
                                                                 String previousVersion) {
        var row = row(request, request.getVersion(), request.getId());
        row.setDeployedAt(NOW);
        if (previousVersion != null) {
            row.setPreviousVersion(previousVersion);
            row.setPreviousRequestId(UUID.randomUUID());
            row.setPreviousDeployedAt(EARLIER);
        }
        return row;
    }
}
