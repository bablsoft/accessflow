package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Default {@link DeploymentVersionTrackerService}. Not {@code @Transactional} — like
 * {@code DeploymentRequestStateService.apply}, callers invoke it from inside their own
 * transaction so the projection commits atomically with the status change it mirrors. Two
 * concurrent writers on one environment resolve on the {@code version_lock} optimistic lock
 * (or, for a first-ever insert race, the unique {@code environment_id} constraint) — the same
 * shape as the outcome-report race documented in {@code DefaultDeploymentOutcomeService}.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentVersionTrackerService implements DeploymentVersionTrackerService {

    private final DeploymentEnvironmentVersionRepository repository;
    private final Clock clock;

    @Override
    public void recordExecution(DeploymentRequestEntity request) {
        var row = repository.findByEnvironmentId(request.getEnvironmentId())
                .orElseGet(() -> newRow(request));
        row.setPreviousVersion(row.getCurrentVersion());
        row.setPreviousRequestId(row.getCurrentRequestId());
        row.setPreviousDeployedAt(row.getDeployedAt());
        row.setCurrentVersion(request.getVersion());
        row.setCurrentRequestId(request.getId());
        row.setDeployedAt(clock.instant());
        row.setLastOutcome(null);
        repository.save(row);
    }

    @Override
    public void recordOutcome(DeploymentRequestEntity request, DeploymentOutcome outcome) {
        var row = repository.findByEnvironmentId(request.getEnvironmentId()).orElse(null);
        if (row == null || !request.getId().equals(row.getCurrentRequestId())) {
            return;
        }
        if (outcome == DeploymentOutcome.FAILED || outcome == DeploymentOutcome.ROLLED_BACK) {
            // Single-level undo: a second consecutive revert finds previous already null and
            // honestly leaves current null — "unknown, see history".
            row.setCurrentVersion(row.getPreviousVersion());
            row.setCurrentRequestId(row.getPreviousRequestId());
            row.setDeployedAt(row.getPreviousDeployedAt());
            row.setPreviousVersion(null);
            row.setPreviousRequestId(null);
            row.setPreviousDeployedAt(null);
        }
        row.setLastOutcome(outcome);
        repository.save(row);
    }

    private static DeploymentEnvironmentVersionEntity newRow(DeploymentRequestEntity request) {
        var row = new DeploymentEnvironmentVersionEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(request.getOrganizationId());
        row.setPipelineId(request.getPipelineId());
        row.setEnvironmentId(request.getEnvironmentId());
        return row;
    }
}
