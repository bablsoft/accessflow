package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.CreateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyNotFoundException;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyPriorityConflictException;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyService;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyView;
import com.bablsoft.accessflow.workflow.api.UpdateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingConditionCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRoutingPolicyService implements RoutingPolicyService {

    // Temporary offset used during reorder so the two-phase priority reassignment never trips the
    // (organization_id, priority) unique index. Larger than any realistic policy count.
    private static final int REORDER_OFFSET = 1_000_000;

    private final RoutingPolicyRepository routingPolicyRepository;
    private final RoutingConditionCodec routingConditionCodec;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<RoutingPolicyView> list(UUID organizationId) {
        return routingPolicyRepository.findAllByOrganizationIdOrderByPriorityAsc(organizationId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoutingPolicyView get(UUID id, UUID organizationId) {
        return toView(loadOrThrow(id, organizationId));
    }

    @Override
    @Transactional
    public RoutingPolicyView create(CreateRoutingPolicyCommand command) {
        validateName(command.name());
        validateActionParams(command.action(), command.requiredApprovals());
        var conditionJson = encodeCondition(command.condition());
        requirePriorityFree(command.organizationId(), command.priority(), null);

        var entity = new RoutingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setDatasourceId(command.datasourceId());
        entity.setName(command.name().trim());
        entity.setDescription(blankToNull(command.description()));
        entity.setPriority(command.priority());
        entity.setEnabled(command.enabled());
        entity.setConditionJson(conditionJson);
        entity.setAction(command.action());
        entity.setRequiredApprovals(normalizedApprovals(command.action(), command.requiredApprovals()));
        entity.setReason(blankToNull(command.reason()));
        return toView(routingPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public RoutingPolicyView update(UUID id, UUID organizationId, UpdateRoutingPolicyCommand command) {
        var entity = loadOrThrow(id, organizationId);
        validateName(command.name());
        validateActionParams(command.action(), command.requiredApprovals());
        var conditionJson = encodeCondition(command.condition());
        requirePriorityFree(organizationId, command.priority(), id);

        entity.setDatasourceId(command.datasourceId());
        entity.setName(command.name().trim());
        entity.setDescription(blankToNull(command.description()));
        entity.setPriority(command.priority());
        entity.setEnabled(command.enabled());
        entity.setConditionJson(conditionJson);
        entity.setAction(command.action());
        entity.setRequiredApprovals(normalizedApprovals(command.action(), command.requiredApprovals()));
        entity.setReason(blankToNull(command.reason()));
        return toView(routingPolicyRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = loadOrThrow(id, organizationId);
        routingPolicyRepository.delete(entity);
    }

    @Override
    @Transactional
    public List<RoutingPolicyView> reorder(UUID organizationId, List<UUID> orderedIds) {
        var existing = routingPolicyRepository.findAllByOrganizationIdOrderByPriorityAsc(organizationId);
        if (orderedIds.size() != existing.size()
                || !new HashSet<>(orderedIds).equals(existing.stream()
                        .map(RoutingPolicyEntity::getId).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_reorder_mismatch"));
        }
        var byId = existing.stream()
                .collect(java.util.stream.Collectors.toMap(RoutingPolicyEntity::getId, e -> e));

        // Phase 1: park every policy at a non-conflicting offset priority.
        int parked = REORDER_OFFSET;
        for (var entity : existing) {
            entity.setPriority(parked++);
        }
        routingPolicyRepository.saveAllAndFlush(existing);

        // Phase 2: assign the final 1..N priorities in the requested order.
        int priority = 1;
        for (UUID id : orderedIds) {
            byId.get(id).setPriority(priority++);
        }
        routingPolicyRepository.saveAllAndFlush(existing);

        return existing.stream()
                .sorted(java.util.Comparator.comparingInt(RoutingPolicyEntity::getPriority))
                .map(this::toView)
                .toList();
    }

    private RoutingPolicyEntity loadOrThrow(UUID id, UUID organizationId) {
        return routingPolicyRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new RoutingPolicyNotFoundException(id));
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_name_required"));
        }
    }

    private void validateActionParams(RoutingAction action, Integer requiredApprovals) {
        if (action == null) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_action_required"));
        }
        boolean needsApprovals = action == RoutingAction.REQUIRE_APPROVALS
                || action == RoutingAction.ESCALATE;
        if (needsApprovals && (requiredApprovals == null || requiredApprovals < 1)) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_approvals_required"));
        }
    }

    private static Integer normalizedApprovals(RoutingAction action, Integer requiredApprovals) {
        return switch (action) {
            case REQUIRE_APPROVALS, ESCALATE -> requiredApprovals;
            case AUTO_APPROVE, AUTO_REJECT -> null;
        };
    }

    private String encodeCondition(ConditionNode condition) {
        if (condition == null) {
            throw new IllegalRoutingPolicyException(msg("error.routing_policy_condition_required"));
        }
        return routingConditionCodec.encode(condition);
    }

    private void requirePriorityFree(UUID organizationId, int priority, UUID selfId) {
        routingPolicyRepository.findByOrganizationIdAndPriority(organizationId, priority)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new RoutingPolicyPriorityConflictException(priority);
                });
    }

    private RoutingPolicyView toView(RoutingPolicyEntity entity) {
        return new RoutingPolicyView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getDatasourceId(),
                entity.getName(),
                entity.getDescription(),
                entity.getPriority(),
                entity.isEnabled(),
                routingConditionCodec.decode(entity.getConditionJson()),
                entity.getAction(),
                entity.getRequiredApprovals(),
                entity.getReason(),
                entity.getVersion(),
                entity.getCreatedAt(),
                normalizedUpdatedAt(entity));
    }

    private static Instant normalizedUpdatedAt(RoutingPolicyEntity entity) {
        return entity.getUpdatedAt() != null ? entity.getUpdatedAt() : entity.getCreatedAt();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
