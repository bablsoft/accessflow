package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyPriorityConflictException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRoutingPolicyException;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRoutingPolicyEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRoutingPolicyRepository;
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingConditionCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeploymentRoutingPolicyServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private DeploymentRoutingPolicyRepository routingPolicyRepository;
    private DeploymentPipelineRepository pipelineRepository;
    private DefaultDeploymentRoutingPolicyService service;

    @BeforeEach
    void setUp() {
        routingPolicyRepository = mock(DeploymentRoutingPolicyRepository.class);
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        var messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new DefaultDeploymentRoutingPolicyService(routingPolicyRepository,
                pipelineRepository,
                new DeploymentRoutingConditionCodec(JsonMapper.builder().build()), messageSource);
        lenient().when(routingPolicyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(routingPolicyRepository.findByOrganizationIdAndPriority(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
    }

    @Test
    void listMapsEveryPolicyInPriorityOrder() {
        when(routingPolicyRepository.findByOrganizationIdOrderByPriorityAsc(ORG))
                .thenReturn(List.of(entity(10, "{\"environments\":[\"prod\"]}"), entity(20, "{}")));

        var views = service.list(ORG);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).priority()).isEqualTo(10);
        assertThat(views.get(0).conditions().environments()).containsExactly("prod");
        assertThat(views.get(1).conditions()).isEqualTo(DeploymentRoutingConditions.NONE);
    }

    @Test
    void listDegradesGracefullyOnAnUnreadableStoredBlob() {
        when(routingPolicyRepository.findByOrganizationIdOrderByPriorityAsc(ORG))
                .thenReturn(List.of(entity(10, "{not json")));

        assertThat(service.list(ORG).get(0).conditions()).isEqualTo(DeploymentRoutingConditions.NONE);
    }

    @Test
    void getReturnsThePolicy() {
        var entity = entity(10, "{}");
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        assertThat(service.get(entity.getId(), ORG).id()).isEqualTo(entity.getId());
    }

    @Test
    void getRejectsACrossOrgId() {
        var id = UUID.randomUUID();
        when(routingPolicyRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, ORG))
                .isInstanceOf(DeploymentRoutingPolicyNotFoundException.class);
    }

    @Test
    void createPersistsTheEncodedConditions() {
        var conditions = new DeploymentRoutingConditions(List.of("production"), null,
                RiskLevel.HIGH, List.of("2.*"), Set.of(5), LocalTime.of(16, 0), LocalTime.of(23, 0),
                "Europe/Berlin");

        var view = service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null, "freeze fridays",
                conditions, DeploymentRoutingAction.REQUIRE_APPROVALS, 2, 10, true));

        assertThat(view.action()).isEqualTo(DeploymentRoutingAction.REQUIRE_APPROVALS);
        assertThat(view.requiredApprovals()).isEqualTo(2);
        assertThat(view.conditions()).isEqualTo(conditions);
    }

    @Test
    void createNullsTheApprovalCountForTheAutoActions() {
        var view = service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null, "auto",
                DeploymentRoutingConditions.NONE, DeploymentRoutingAction.AUTO_APPROVE, 5, 10, true));

        assertThat(view.requiredApprovals()).isNull();
    }

    @Test
    void createRequiresAnApprovalCountForRequireApprovalsAndEscalate() {
        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null,
                "p", DeploymentRoutingConditions.NONE, DeploymentRoutingAction.REQUIRE_APPROVALS,
                null, 10, true)))
                .isInstanceOf(IllegalDeploymentRoutingPolicyException.class)
                .hasMessage("error.deployment_routing_policy_approvals_required");
        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null,
                "p", DeploymentRoutingConditions.NONE, DeploymentRoutingAction.ESCALATE, 0, 10, true)))
                .isInstanceOf(IllegalDeploymentRoutingPolicyException.class);
    }

    @Test
    void createValidatesTheTimezone() {
        var conditions = new DeploymentRoutingConditions(null, null, null, null, Set.of(1),
                LocalTime.of(9, 0), LocalTime.of(17, 0), "Mars/Olympus");

        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null,
                "p", conditions, DeploymentRoutingAction.AUTO_APPROVE, null, 10, true)))
                .isInstanceOf(IllegalDeploymentRoutingPolicyException.class)
                .hasMessage("error.deployment_routing_policy_invalid_timezone");
    }

    @Test
    void createValidatesDayNumbers() {
        var conditions = new DeploymentRoutingConditions(null, null, null, null, Set.of(9), null,
                null, null);

        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null,
                "p", conditions, DeploymentRoutingAction.AUTO_APPROVE, null, 10, true)))
                .isInstanceOf(IllegalDeploymentRoutingPolicyException.class)
                .hasMessage("error.deployment_routing_policy_invalid_days");
    }

    @Test
    void createRejectsEqualStartAndEndTimes() {
        var conditions = new DeploymentRoutingConditions(null, null, null, null, null,
                LocalTime.of(9, 0), LocalTime.of(9, 0), null);

        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null,
                "p", conditions, DeploymentRoutingAction.AUTO_APPROVE, null, 10, true)))
                .isInstanceOf(IllegalDeploymentRoutingPolicyException.class)
                .hasMessage("error.deployment_routing_policy_invalid_times");
    }

    @Test
    void createRejectsAPipelineFromAnotherOrganization() {
        var pipelineId = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG,
                pipelineId, "p", DeploymentRoutingConditions.NONE,
                DeploymentRoutingAction.AUTO_APPROVE, null, 10, true)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void createRejectsATakenPriority() {
        when(routingPolicyRepository.findByOrganizationIdAndPriority(ORG, 10))
                .thenReturn(Optional.of(entity(10, "{}")));

        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null,
                "p", DeploymentRoutingConditions.NONE, DeploymentRoutingAction.AUTO_APPROVE, null,
                10, true)))
                .isInstanceOf(DeploymentRoutingPolicyPriorityConflictException.class);
        verify(routingPolicyRepository, never()).saveAndFlush(any());
    }

    @Test
    void aConcurrentWriteLosingTheUniqueIndexBecomesAPriorityConflict() {
        when(routingPolicyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_deployment_routing_policies_org_priority"));

        assertThatThrownBy(() -> service.create(new CreateDeploymentRoutingPolicyCommand(ORG, null,
                "p", DeploymentRoutingConditions.NONE, DeploymentRoutingAction.AUTO_APPROVE, null,
                10, true)))
                .isInstanceOf(DeploymentRoutingPolicyPriorityConflictException.class);
    }

    @Test
    void updateLeavesNullFieldsUnchanged() {
        var entity = entity(10, "{\"environments\":[\"prod\"]}");
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        var view = service.update(entity.getId(), ORG, new UpdateDeploymentRoutingPolicyCommand(
                null, null, null, null, null, null, null, null));

        assertThat(view.name()).isEqualTo("policy-10");
        assertThat(view.priority()).isEqualTo(10);
        assertThat(view.conditions().environments()).containsExactly("prod");
        assertThat(view.enabled()).isTrue();
    }

    @Test
    void updateAppliesEveryProvidedField() {
        var entity = entity(10, "{}");
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));
        var conditions = new DeploymentRoutingConditions(List.of("staging"), null, null, null, null,
                null, null, null);

        var view = service.update(entity.getId(), ORG, new UpdateDeploymentRoutingPolicyCommand(
                null, null, "renamed", conditions, DeploymentRoutingAction.ESCALATE, 2, 20, false));

        assertThat(view.name()).isEqualTo("renamed");
        assertThat(view.action()).isEqualTo(DeploymentRoutingAction.ESCALATE);
        assertThat(view.requiredApprovals()).isEqualTo(2);
        assertThat(view.priority()).isEqualTo(20);
        assertThat(view.enabled()).isFalse();
        assertThat(view.conditions().environments()).containsExactly("staging");
    }

    @Test
    void updateClearPipelineWinsOverASuppliedPipelineId() {
        var entity = entity(10, "{}");
        entity.setPipelineId(UUID.randomUUID());
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        var view = service.update(entity.getId(), ORG, new UpdateDeploymentRoutingPolicyCommand(
                UUID.randomUUID(), true, null, null, null, null, null, null));

        assertThat(view.pipelineId()).isNull();
        verify(pipelineRepository, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void updateExcludesItselfFromThePriorityGuard() {
        var entity = entity(10, "{}");
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));
        when(routingPolicyRepository.findByOrganizationIdAndPriority(ORG, 20))
                .thenReturn(Optional.of(entity));

        var view = service.update(entity.getId(), ORG, new UpdateDeploymentRoutingPolicyCommand(
                null, null, null, null, null, null, 20, null));

        assertThat(view.priority()).isEqualTo(20);
    }

    @Test
    void updateRejectsAPriorityTakenByAnotherPolicy() {
        var entity = entity(10, "{}");
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));
        when(routingPolicyRepository.findByOrganizationIdAndPriority(ORG, 20))
                .thenReturn(Optional.of(entity(20, "{}")));

        assertThatThrownBy(() -> service.update(entity.getId(), ORG,
                new UpdateDeploymentRoutingPolicyCommand(null, null, null, null, null, null, 20, null)))
                .isInstanceOf(DeploymentRoutingPolicyPriorityConflictException.class);
    }

    @Test
    void updateRevalidatesTheActionAgainstTheExistingApprovalCount() {
        var entity = entity(10, "{}");
        entity.setRequiredApprovals(null);
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(entity.getId(), ORG,
                new UpdateDeploymentRoutingPolicyCommand(null, null, null, null,
                        DeploymentRoutingAction.ESCALATE, null, null, null)))
                .isInstanceOf(IllegalDeploymentRoutingPolicyException.class);
    }

    @Test
    void updateValidatesANewlySuppliedPipeline() {
        var entity = entity(10, "{}");
        var pipelineId = UUID.randomUUID();
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, ORG))
                .thenReturn(Optional.of(new DeploymentPipelineEntity()));

        var view = service.update(entity.getId(), ORG, new UpdateDeploymentRoutingPolicyCommand(
                pipelineId, null, null, null, null, null, null, null));

        assertThat(view.pipelineId()).isEqualTo(pipelineId);
    }

    @Test
    void deleteRemovesThePolicy() {
        var entity = entity(10, "{}");
        when(routingPolicyRepository.findByIdAndOrganizationId(entity.getId(), ORG))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), ORG);

        verify(routingPolicyRepository).delete(entity);
    }

    @Test
    void deleteRejectsACrossOrgId() {
        var id = UUID.randomUUID();
        when(routingPolicyRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, ORG))
                .isInstanceOf(DeploymentRoutingPolicyNotFoundException.class);
    }

    private static DeploymentRoutingPolicyEntity entity(int priority, String conditions) {
        var entity = new DeploymentRoutingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setName("policy-" + priority);
        entity.setConditions(conditions);
        entity.setAction(DeploymentRoutingAction.AUTO_APPROVE);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return entity;
    }
}
