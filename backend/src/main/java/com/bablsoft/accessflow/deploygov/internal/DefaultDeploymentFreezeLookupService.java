package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.ActiveDeploymentFreezeView;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Exposes {@link FreezeWindowEvaluator} to other modules (#877). Pure delegation: the evaluator
 * picks the winning window (most-specific scope, then {@code REJECT} over {@code HOLD}, failing
 * closed to {@code HOLD} on an unevaluable row) and this service only drops the ranking fields the
 * consumer has no use for.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultDeploymentFreezeLookupService implements DeploymentFreezeLookupService {

    private final FreezeWindowEvaluator evaluator;

    @Override
    public Optional<ActiveDeploymentFreezeView> evaluate(UUID organizationId, UUID pipelineId,
                                                         UUID environmentId) {
        return evaluator.evaluate(organizationId, pipelineId, environmentId).map(this::toView);
    }

    @Override
    public Optional<ActiveDeploymentFreezeView> evaluate(UUID organizationId, UUID pipelineId,
                                                         UUID environmentId, Instant at) {
        return evaluator.evaluate(organizationId, pipelineId, environmentId, at).map(this::toView);
    }

    private ActiveDeploymentFreezeView toView(FreezeWindowEvaluator.ActiveFreeze freeze) {
        return new ActiveDeploymentFreezeView(freeze.windowId(), freeze.behavior(), freeze.reason());
    }
}
