package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.CreateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyPriorityConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyView;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRoutingPolicyException;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRoutingPolicyEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRoutingPolicyRepository;
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingConditionCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD over deployment routing policies (#691). Conditions are validated here rather than at
 * evaluation time: these policies are hand-authored through the API, so a bad timezone or an
 * approval count that contradicts the action must fail loudly at write time, not silently change
 * how deployments route.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentRoutingPolicyService implements DeploymentRoutingPolicyService {

    private static final String PRIORITY_INDEX = "uq_deployment_routing_policies_org_priority";

    private final DeploymentRoutingPolicyRepository routingPolicyRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentRoutingConditionCodec conditionCodec;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<DeploymentRoutingPolicyView> list(UUID organizationId) {
        return routingPolicyRepository.findByOrganizationIdOrderByPriorityAsc(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentRoutingPolicyView get(UUID id, UUID organizationId) {
        return toView(require(id, organizationId));
    }

    @Override
    @Transactional
    public DeploymentRoutingPolicyView create(CreateDeploymentRoutingPolicyCommand command) {
        validateAction(command.action(), command.requiredApprovals());
        validateConditions(command.conditions());
        requirePipelineInOrg(command.pipelineId(), command.organizationId());
        requirePriorityFree(command.organizationId(), command.priority(), null);

        var entity = new DeploymentRoutingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setPipelineId(command.pipelineId());
        entity.setName(command.name());
        entity.setConditions(conditionCodec.toJson(command.conditions()));
        entity.setAction(command.action());
        entity.setRequiredApprovals(normalizedApprovals(command.action(), command.requiredApprovals()));
        entity.setPriority(command.priority());
        entity.setEnabled(command.enabled());
        entity.setCreatedAt(Instant.now());
        return toView(save(entity, command.priority()));
    }

    @Override
    @Transactional
    public DeploymentRoutingPolicyView update(UUID id, UUID organizationId,
                                              UpdateDeploymentRoutingPolicyCommand command) {
        var entity = require(id, organizationId);
        var action = command.action() != null ? command.action() : entity.getAction();
        var requiredApprovals = command.requiredApprovals() != null
                ? command.requiredApprovals() : entity.getRequiredApprovals();
        validateAction(action, requiredApprovals);

        if (Boolean.TRUE.equals(command.clearPipeline())) {
            entity.setPipelineId(null);
        } else if (command.pipelineId() != null) {
            requirePipelineInOrg(command.pipelineId(), organizationId);
            entity.setPipelineId(command.pipelineId());
        }
        if (command.name() != null) {
            entity.setName(command.name());
        }
        if (command.conditions() != null) {
            validateConditions(command.conditions());
            entity.setConditions(conditionCodec.toJson(command.conditions()));
        }
        if (command.priority() != null && command.priority() != entity.getPriority()) {
            requirePriorityFree(organizationId, command.priority(), entity.getId());
            entity.setPriority(command.priority());
        }
        if (command.enabled() != null) {
            entity.setEnabled(command.enabled());
        }
        entity.setAction(action);
        entity.setRequiredApprovals(normalizedApprovals(action, requiredApprovals));
        return toView(save(entity, entity.getPriority()));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        routingPolicyRepository.delete(require(id, organizationId));
    }

    private DeploymentRoutingPolicyEntity save(DeploymentRoutingPolicyEntity entity, int priority) {
        try {
            return routingPolicyRepository.saveAndFlush(entity);
        } catch (DuplicateKeyException ex) {
            // Only the priority index is translated; any other constraint must keep its own
            // identity rather than surfacing to the admin as a misleading "priority in use" 409.
            if (namesPriorityIndex(ex)) {
                throw new DeploymentRoutingPolicyPriorityConflictException(priority);
            }
            throw ex;
        }
    }

    private static boolean namesPriorityIndex(DuplicateKeyException ex) {
        var cause = ex.getMostSpecificCause().getMessage();
        return cause != null && cause.contains(PRIORITY_INDEX);
    }

    private DeploymentRoutingPolicyEntity require(UUID id, UUID organizationId) {
        return routingPolicyRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new DeploymentRoutingPolicyNotFoundException(id));
    }

    private void requirePipelineInOrg(UUID pipelineId, UUID organizationId) {
        if (pipelineId == null) {
            return;
        }
        pipelineRepository.findByIdAndOrganizationId(pipelineId, organizationId)
                .orElseThrow(() -> new DeploymentPipelineNotFoundException(pipelineId));
    }

    private void requirePriorityFree(UUID organizationId, int priority, UUID selfId) {
        routingPolicyRepository.findByOrganizationIdAndPriority(organizationId, priority)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new DeploymentRoutingPolicyPriorityConflictException(priority);
                });
    }

    private void validateAction(DeploymentRoutingAction action, Integer requiredApprovals) {
        boolean needsApprovals = action == DeploymentRoutingAction.REQUIRE_APPROVALS
                || action == DeploymentRoutingAction.ESCALATE;
        if (needsApprovals && (requiredApprovals == null || requiredApprovals < 1)) {
            throw new IllegalDeploymentRoutingPolicyException(
                    msg("error.deployment_routing_policy_approvals_required"));
        }
    }

    private void validateConditions(DeploymentRoutingConditions conditions) {
        if (conditions == null) {
            return;
        }
        for (var day : conditions.daysOfWeek()) {
            if (day == null || day < 1 || day > 7) {
                throw new IllegalDeploymentRoutingPolicyException(
                        msg("error.deployment_routing_policy_invalid_days", String.valueOf(day)));
            }
        }
        // Exactly one of the two times is the dangerous shape: the engine cannot evaluate a
        // half-open window, and treating it as unconstrained would silently widen the policy to
        // every deployment. Reject it here so a bad definition never reaches the engine.
        if ((conditions.startTime() == null) != (conditions.endTime() == null)) {
            throw new IllegalDeploymentRoutingPolicyException(
                    msg("error.deployment_routing_policy_incomplete_times"));
        }
        if (conditions.startTime() != null && conditions.startTime().equals(conditions.endTime())) {
            throw new IllegalDeploymentRoutingPolicyException(
                    msg("error.deployment_routing_policy_invalid_times"));
        }
        if (conditions.timezone() != null && !conditions.timezone().isBlank()) {
            try {
                ZoneId.of(conditions.timezone());
            } catch (DateTimeException ex) {
                throw new IllegalDeploymentRoutingPolicyException(
                        msg("error.deployment_routing_policy_invalid_timezone", conditions.timezone()));
            }
        }
    }

    private static Integer normalizedApprovals(DeploymentRoutingAction action, Integer requiredApprovals) {
        return switch (action) {
            case REQUIRE_APPROVALS, ESCALATE -> requiredApprovals;
            case AUTO_APPROVE, AUTO_REJECT -> null;
        };
    }

    private DeploymentRoutingPolicyView toView(DeploymentRoutingPolicyEntity entity) {
        DeploymentRoutingConditions conditions;
        try {
            conditions = conditionCodec.fromJson(entity.getConditions());
        } catch (DeploymentRoutingConditionCodec.ConditionsParseException ex) {
            // A blob written before a validation rule existed still has to be listable so an admin
            // can see and fix it; the engine skips such a policy rather than matching it.
            conditions = DeploymentRoutingConditions.NONE;
        }
        return new DeploymentRoutingPolicyView(entity.getId(), entity.getOrganizationId(),
                entity.getPipelineId(), entity.getName(), conditions, entity.getAction(),
                entity.getRequiredApprovals(), entity.getPriority(), entity.isEnabled(),
                entity.getCreatedAt());
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
