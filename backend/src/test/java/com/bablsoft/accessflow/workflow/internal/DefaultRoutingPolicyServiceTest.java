package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.CreateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyNotFoundException;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyPriorityConflictException;
import com.bablsoft.accessflow.workflow.api.UpdateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingConditionCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRoutingPolicyServiceTest {

    @Mock RoutingPolicyRepository routingPolicyRepository;

    private final RoutingConditionCodec codec = new RoutingConditionCodec(
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
            messageSource());
    private DefaultRoutingPolicyService service;

    private final UUID orgId = UUID.randomUUID();
    private final ConditionNode condition =
            new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE));

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @BeforeEach
    void setUp() {
        service = new DefaultRoutingPolicyService(routingPolicyRepository, codec, messageSource());
    }

    @Test
    void createPersistsPolicy() {
        when(routingPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.create(new CreateRoutingPolicyCommand(orgId, null, "Block deletes",
                "desc", 1, true, condition, RoutingAction.AUTO_REJECT, null, "blocked"));

        assertThat(view.name()).isEqualTo("Block deletes");
        assertThat(view.action()).isEqualTo(RoutingAction.AUTO_REJECT);
        assertThat(view.condition()).isEqualTo(condition);
        assertThat(view.requiredApprovals()).isNull();
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create(new CreateRoutingPolicyCommand(orgId, null, "  ",
                null, 1, true, condition, RoutingAction.AUTO_REJECT, null, null)))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void createRejectsNullAction() {
        assertThatThrownBy(() -> service.create(new CreateRoutingPolicyCommand(orgId, null, "x",
                null, 1, true, condition, null, null, null)))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void createRejectsRequireApprovalsWithoutCount() {
        assertThatThrownBy(() -> service.create(new CreateRoutingPolicyCommand(orgId, null, "x",
                null, 1, true, condition, RoutingAction.REQUIRE_APPROVALS, null, null)))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void createRejectsEscalateWithZeroCount() {
        assertThatThrownBy(() -> service.create(new CreateRoutingPolicyCommand(orgId, null, "x",
                null, 1, true, condition, RoutingAction.ESCALATE, 0, null)))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void createKeepsApprovalsForEscalate() {
        when(routingPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.create(new CreateRoutingPolicyCommand(orgId, null, "Escalate",
                null, 1, true, condition, RoutingAction.ESCALATE, 2, null));

        assertThat(view.requiredApprovals()).isEqualTo(2);
    }

    @Test
    void createRejectsNullCondition() {
        assertThatThrownBy(() -> service.create(new CreateRoutingPolicyCommand(orgId, null, "x",
                null, 1, true, null, RoutingAction.AUTO_REJECT, null, null)))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void createRejectsPriorityConflict() {
        var existing = new RoutingPolicyEntity();
        existing.setId(UUID.randomUUID());
        when(routingPolicyRepository.findByOrganizationIdAndPriority(orgId, 1))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(new CreateRoutingPolicyCommand(orgId, null, "x",
                null, 1, true, condition, RoutingAction.AUTO_REJECT, null, null)))
                .isInstanceOf(RoutingPolicyPriorityConflictException.class);
    }

    @Test
    void getThrowsWhenNotFound() {
        var id = UUID.randomUUID();
        when(routingPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, orgId))
                .isInstanceOf(RoutingPolicyNotFoundException.class);
    }

    @Test
    void updateReplacesFields() {
        var entity = persisted(1, RoutingAction.AUTO_REJECT);
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        when(routingPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(entity.getId(), orgId, new UpdateRoutingPolicyCommand(null,
                "Renamed", null, 1, false, condition, RoutingAction.REQUIRE_APPROVALS, 2, null));

        assertThat(view.name()).isEqualTo("Renamed");
        assertThat(view.enabled()).isFalse();
        assertThat(view.action()).isEqualTo(RoutingAction.REQUIRE_APPROVALS);
        assertThat(view.requiredApprovals()).isEqualTo(2);
    }

    @Test
    void updateAllowsKeepingSamePriority() {
        var entity = persisted(5, RoutingAction.AUTO_REJECT);
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        when(routingPolicyRepository.findByOrganizationIdAndPriority(orgId, 5))
                .thenReturn(Optional.of(entity)); // the same row holds priority 5
        when(routingPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(entity.getId(), orgId, new UpdateRoutingPolicyCommand(null,
                "x", null, 5, true, condition, RoutingAction.AUTO_REJECT, null, null));

        assertThat(view.priority()).isEqualTo(5);
    }

    @Test
    void deleteThrowsWhenNotFound() {
        var id = UUID.randomUUID();
        when(routingPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, orgId))
                .isInstanceOf(RoutingPolicyNotFoundException.class);
    }

    @Test
    void deleteRemovesPolicy() {
        var entity = persisted(1, RoutingAction.AUTO_REJECT);
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), orgId);

        verify(routingPolicyRepository).delete(entity);
    }

    @Test
    void reorderRejectsMismatchedIdSet() {
        var a = persisted(1, RoutingAction.AUTO_REJECT);
        when(routingPolicyRepository.findAllByOrganizationIdOrderByPriorityAsc(orgId))
                .thenReturn(List.of(a));

        assertThatThrownBy(() -> service.reorder(orgId, List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalRoutingPolicyException.class);
    }

    @Test
    void reorderAssignsSequentialPriorities() {
        var a = persisted(1, RoutingAction.AUTO_REJECT);
        var b = persisted(2, RoutingAction.AUTO_APPROVE);
        when(routingPolicyRepository.findAllByOrganizationIdOrderByPriorityAsc(orgId))
                .thenReturn(List.of(a, b));
        when(routingPolicyRepository.saveAllAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.reorder(orgId, List.of(b.getId(), a.getId()));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(b.getId());
        assertThat(result.get(0).priority()).isEqualTo(1);
        assertThat(result.get(1).id()).isEqualTo(a.getId());
        assertThat(result.get(1).priority()).isEqualTo(2);
    }

    @Test
    void listMapsToViews() {
        when(routingPolicyRepository.findAllByOrganizationIdOrderByPriorityAsc(orgId))
                .thenReturn(List.of(persisted(1, RoutingAction.AUTO_REJECT)));

        assertThat(service.list(orgId)).hasSize(1);
    }

    private RoutingPolicyEntity persisted(int priority, RoutingAction action) {
        var entity = new RoutingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setName("policy-" + priority);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setAction(action);
        entity.setConditionJson(codec.encode(condition));
        return entity;
    }
}
