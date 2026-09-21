package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanNotFoundException;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentSortOrderConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentEnvironmentNameException;
import com.bablsoft.accessflow.deploygov.api.DuplicateDeploymentPipelineNameException;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.UpdateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentPipelineAdminServiceTest {

    @Mock
    private DeploymentPipelineRepository pipelineRepository;

    @Mock
    private DeploymentEnvironmentRepository environmentRepository;

    @Mock
    private ReviewPlanLookupService reviewPlanLookupService;

    @Mock
    private DatasourceAdminService datasourceAdminService;

    @InjectMocks
    private DefaultDeploymentPipelineAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();

    @Test
    void listMapsPage() {
        when(pipelineRepository.findByOrganizationId(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pipeline())));

        var page = service.list(orgId, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).name()).isEqualTo("payments-api");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void getReturnsViewForOwnOrganization() {
        var entity = pipeline();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(entity));

        var view = service.get(pipelineId, orgId);

        assertThat(view.id()).isEqualTo(pipelineId);
        assertThat(view.provider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
    }

    @Test
    void getThrowsWhenMissingOrCrossOrg() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(pipelineId, orgId))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void createPersistsWithDefaults() {
        when(pipelineRepository.existsByOrganizationIdAndName(orgId, "payments-api"))
                .thenReturn(false);
        when(pipelineRepository.save(any(DeploymentPipelineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.create(new CreateDeploymentPipelineCommand(orgId, "payments-api",
                PipelineProvider.GITHUB_ACTIONS, null, null, null, null, null));

        assertThat(view.aiAnalysisEnabled()).isTrue();
        assertThat(view.active()).isTrue();
        assertThat(view.organizationId()).isEqualTo(orgId);
    }

    @Test
    void createRejectsDuplicateName() {
        when(pipelineRepository.existsByOrganizationIdAndName(orgId, "payments-api"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateDeploymentPipelineCommand(orgId,
                "payments-api", PipelineProvider.GENERIC, null, null, null, null, null)))
                .isInstanceOf(DuplicateDeploymentPipelineNameException.class);
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void createAssignsReviewPlanFromCallersOrganization() {
        var planId = UUID.randomUUID();
        when(pipelineRepository.existsByOrganizationIdAndName(orgId, "payments-api"))
                .thenReturn(false);
        when(reviewPlanLookupService.findById(planId))
                .thenReturn(Optional.of(planSnapshot(planId, orgId)));
        when(pipelineRepository.save(any(DeploymentPipelineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.create(new CreateDeploymentPipelineCommand(orgId, "payments-api",
                PipelineProvider.GITLAB_CI, null, null, planId, null, null));

        assertThat(view.reviewPlanId()).isEqualTo(planId);
    }

    @Test
    void createRejectsReviewPlanFromAnotherOrganization() {
        var planId = UUID.randomUUID();
        when(pipelineRepository.existsByOrganizationIdAndName(orgId, "payments-api"))
                .thenReturn(false);
        when(reviewPlanLookupService.findById(planId))
                .thenReturn(Optional.of(planSnapshot(planId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.create(new CreateDeploymentPipelineCommand(orgId,
                "payments-api", PipelineProvider.GITLAB_CI, null, null, planId, null, null)))
                .isInstanceOf(ReviewPlanNotFoundException.class);
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void updateChangesFieldsAndChecksNameCollision() {
        var entity = pipeline();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(entity));
        when(pipelineRepository.existsByOrganizationIdAndName(orgId, "billing-api"))
                .thenReturn(false);
        when(pipelineRepository.save(any(DeploymentPipelineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(pipelineId, orgId, new UpdateDeploymentPipelineCommand(
                "billing-api", PipelineProvider.JENKINS, "https://git.example/billing", "billing",
                null, null, false, null, null, false));

        assertThat(view.name()).isEqualTo("billing-api");
        assertThat(view.provider()).isEqualTo(PipelineProvider.JENKINS);
        assertThat(view.aiAnalysisEnabled()).isFalse();
        assertThat(view.active()).isFalse();
    }

    @Test
    void updateRejectsDuplicateNameOnRename() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(pipelineRepository.existsByOrganizationIdAndName(orgId, "taken"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.update(pipelineId, orgId,
                new UpdateDeploymentPipelineCommand("taken", null, null, null, null, null, null,
                        null, null, null)))
                .isInstanceOf(DuplicateDeploymentPipelineNameException.class);
    }

    @Test
    void updateSameNameSkipsCollisionCheck() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(pipelineRepository.save(any(DeploymentPipelineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(pipelineId, orgId, new UpdateDeploymentPipelineCommand(
                "payments-api", null, null, null, null, null, null, null, null, null));

        assertThat(view.name()).isEqualTo("payments-api");
        verify(pipelineRepository, never()).existsByOrganizationIdAndName(any(), any());
    }

    @Test
    void updateClearReviewPlanWinsOverProvidedPlanId() {
        var entity = pipeline();
        entity.setReviewPlanId(UUID.randomUUID());
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(entity));
        when(pipelineRepository.save(any(DeploymentPipelineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(pipelineId, orgId, new UpdateDeploymentPipelineCommand(
                null, null, null, null, UUID.randomUUID(), true, null, null, null, null));

        assertThat(view.reviewPlanId()).isNull();
        verify(reviewPlanLookupService, never()).findById(any());
    }

    @Test
    void updateClearAiConfigUnassignsExistingConfig() {
        var entity = pipeline();
        entity.setAiConfigId(UUID.randomUUID());
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(entity));
        when(pipelineRepository.save(any(DeploymentPipelineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(pipelineId, orgId, new UpdateDeploymentPipelineCommand(
                null, null, null, null, null, null, null, null, true, null));

        assertThat(view.aiConfigId()).isNull();
    }

    @Test
    void deleteRemovesOwnOrgPipeline() {
        var entity = pipeline();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(entity));

        service.delete(pipelineId, orgId);

        verify(pipelineRepository).delete(entity);
    }

    @Test
    void deleteThrowsWhenCrossOrg() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(pipelineId, orgId))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
        verify(pipelineRepository, never()).delete(any(DeploymentPipelineEntity.class));
    }

    @Test
    void listEnvironmentsReturnsOrderedViews() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId))
                .thenReturn(List.of(environment("staging", 0), environment("production", 1)));

        var environments = service.listEnvironments(pipelineId, orgId);

        assertThat(environments).extracting(v -> v.name())
                .containsExactly("staging", "production");
    }

    @Test
    void createEnvironmentPersistsWithDefaults() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        // An empty pipeline has no max — the first environment lands at 0 (Mockito would return 0
        // for the Integer by default, which would hide the null branch).
        when(environmentRepository.findMaxSortOrderByPipelineId(pipelineId)).thenReturn(null);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null, null, null));

        assertThat(view.sortOrder()).isZero();
        assertThat(view.requireReview()).isTrue();
        assertThat(view.allowBreakGlass()).isFalse();
    }

    @Test
    void createEnvironmentRejectsDuplicateName() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null, null, null)))
                .isInstanceOf(DuplicateDeploymentEnvironmentNameException.class);
    }

    @Test
    void createEnvironmentRejectsReviewPlanFromAnotherOrganization() {
        var planId = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(reviewPlanLookupService.findById(planId))
                .thenReturn(Optional.of(planSnapshot(planId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, 2, planId, null, null, null)))
                .isInstanceOf(ReviewPlanNotFoundException.class);
    }

    @Test
    void updateEnvironmentMutatesFieldsAndClearsOverrides() {
        var environment = environment("staging", 0);
        environment.setRequiredApprovals(3);
        environment.setReviewPlanId(UUID.randomUUID());
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, 5, false, null, true, null, true, true, null,
                        null, null));

        assertThat(view.sortOrder()).isEqualTo(5);
        assertThat(view.requireReview()).isFalse();
        assertThat(view.requiredApprovals()).isNull();
        assertThat(view.reviewPlanId()).isNull();
        assertThat(view.allowBreakGlass()).isTrue();
    }

    @Test
    void createEnvironmentNormalizesTags() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var tags = new java.util.ArrayList<String>();
        tags.add(" acme ");
        tags.add("");
        tags.add(null);
        tags.add("acme");
        tags.add("eu");
        var view = service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null,
                        tags, null));

        assertThat(view.tags()).containsExactly("acme", "eu");
    }

    @Test
    void createEnvironmentDefaultsToNoTags() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null,
                        null, null));

        assertThat(view.tags()).isEmpty();
    }

    @Test
    void updateEnvironmentReplacesTags() {
        var environment = environment("staging", 0);
        environment.setTags(new String[] {"acme"});
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, List.of("globex", "us"), null, null));

        assertThat(view.tags()).containsExactly("globex", "us");
    }

    @Test
    void updateEnvironmentNullTagsLeavesTagsUnchanged() {
        var environment = environment("staging", 0);
        environment.setTags(new String[] {"acme"});
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, null, null, null));

        assertThat(view.tags()).containsExactly("acme");
    }

    @Test
    void updateEnvironmentEmptyTagListClearsTags() {
        var environment = environment("staging", 0);
        environment.setTags(new String[] {"acme"});
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, List.of(), null, null));

        assertThat(view.tags()).isEmpty();
    }

    @Test
    void updateEnvironmentRejectsEnvironmentOfAnotherPipeline() {
        var foreign = environment("staging", 0);
        foreign.setPipelineId(UUID.randomUUID());
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.updateEnvironment(pipelineId, orgId, foreign.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, null, null, null)))
                .isInstanceOf(DeploymentEnvironmentNotFoundException.class);
    }

    @Test
    void updateEnvironmentRejectsDuplicateNameOnRename() {
        var environment = environment("staging", 0);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand("production", null, null, null, null, null,
                        null, null, null, null, null)))
                .isInstanceOf(DuplicateDeploymentEnvironmentNameException.class);
    }

    @Test
    void deleteEnvironmentRemovesOwnedEnvironment() {
        var environment = environment("staging", 0);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));

        service.deleteEnvironment(pipelineId, orgId, environment.getId());

        verify(environmentRepository).delete(environment);
    }

    @Test
    void deleteEnvironmentRejectsMissingEnvironment() {
        var environmentId = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEnvironment(pipelineId, orgId, environmentId))
                .isInstanceOf(DeploymentEnvironmentNotFoundException.class);
    }

    @Test
    void createEnvironmentAppendsToTheLadderWhenSortOrderOmitted() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.findMaxSortOrderByPipelineId(pipelineId)).thenReturn(4);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null, null, null));

        assertThat(view.sortOrder()).isEqualTo(5);
        // An appended position is free by construction — no duplicate pre-check is spent on it.
        verify(environmentRepository, never()).existsByPipelineIdAndSortOrder(any(), anyInt());
    }

    @Test
    void createEnvironmentRejectsDuplicateSortOrder() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.existsByPipelineIdAndSortOrder(pipelineId, 1)).thenReturn(true);

        assertThatThrownBy(() -> service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", 1, null, null, null, null, null, null)))
                .isInstanceOf(DeploymentEnvironmentSortOrderConflictException.class)
                .satisfies(ex -> assertThat(((DeploymentEnvironmentSortOrderConflictException) ex)
                        .getSortOrder()).isEqualTo(1));
        verify(environmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void createEnvironmentTranslatesRacedSortOrderViolation() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.existsByPipelineIdAndSortOrder(pipelineId, 2)).thenReturn(false);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \""
                        + DefaultDeploymentPipelineAdminService.SORT_ORDER_CONSTRAINT + "\""));

        assertThatThrownBy(() -> service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", 2, null, null, null, null, null, null)))
                .isInstanceOf(DeploymentEnvironmentSortOrderConflictException.class);
    }

    @Test
    void createEnvironmentRethrowsUnrelatedIntegrityViolation() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_deployment_environments_pipeline_name\""));

        assertThatThrownBy(() -> service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createEnvironmentRethrowsAViolationWithoutAMessage() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenThrow(new DataIntegrityViolationException(null));

        assertThatThrownBy(() -> service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNot(new org.assertj.core.api.Condition<>(
                        DeploymentEnvironmentSortOrderConflictException.class::isInstance, "translated"));
    }

    @Test
    void createEnvironmentBindsDatasourceFromCallersOrganization() {
        var datasourceId = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null, null,
                        datasourceId));

        assertThat(view.datasourceId()).isEqualTo(datasourceId);
        verify(datasourceAdminService).getForAdmin(datasourceId, orgId);
    }

    @Test
    void createEnvironmentRejectsDatasourceFromAnotherOrganization() {
        var datasourceId = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.existsByPipelineIdAndName(pipelineId, "production"))
                .thenReturn(false);
        when(datasourceAdminService.getForAdmin(datasourceId, orgId))
                .thenThrow(new DatasourceNotFoundException(datasourceId));

        assertThatThrownBy(() -> service.createEnvironment(pipelineId, orgId,
                new CreateDeploymentEnvironmentCommand("production", null, null, null, null, null, null,
                        datasourceId)))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(environmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateEnvironmentBindsDatasource() {
        var datasourceId = UUID.randomUUID();
        var environment = environment("staging", 0);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, null, datasourceId, null));

        assertThat(view.datasourceId()).isEqualTo(datasourceId);
        verify(datasourceAdminService).getForAdmin(datasourceId, orgId);
    }

    @Test
    void updateEnvironmentRejectsDatasourceFromAnotherOrganization() {
        var datasourceId = UUID.randomUUID();
        var environment = environment("staging", 0);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(datasourceAdminService.getForAdmin(datasourceId, orgId))
                .thenThrow(new DatasourceNotFoundException(datasourceId));

        assertThatThrownBy(() -> service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, null, datasourceId, null)))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(environmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateEnvironmentClearDatasourceWinsOverProvidedId() {
        var environment = environment("staging", 0);
        environment.setDatasourceId(UUID.randomUUID());
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, null, UUID.randomUUID(), true));

        assertThat(view.datasourceId()).isNull();
        verify(datasourceAdminService, never()).getForAdmin(any(), any());
    }

    @Test
    void updateEnvironmentNullDatasourceLeavesBindingUnchanged() {
        var datasourceId = UUID.randomUUID();
        var environment = environment("staging", 0);
        environment.setDatasourceId(datasourceId);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, null, null, null, null, null, null,
                        null, null, null, null));

        assertThat(view.datasourceId()).isEqualTo(datasourceId);
    }

    @Test
    void updateEnvironmentRejectsSortOrderHeldByASibling() {
        var environment = environment("staging", 0);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.existsByPipelineIdAndSortOrderAndIdNot(pipelineId, 1, environment.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, 1, null, null, null, null, null,
                        null, null, null, null)))
                .isInstanceOf(DeploymentEnvironmentSortOrderConflictException.class);
        verify(environmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateEnvironmentSameSortOrderSkipsTheConflictCheck() {
        var environment = environment("staging", 3);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, 3, null, null, null, null, null,
                        null, null, null, null));

        assertThat(view.sortOrder()).isEqualTo(3);
        verify(environmentRepository, never())
                .existsByPipelineIdAndSortOrderAndIdNot(any(), anyInt(), any());
    }

    @Test
    void updateEnvironmentTranslatesRacedSortOrderViolation() {
        var environment = environment("staging", 0);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline()));
        when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        when(environmentRepository.existsByPipelineIdAndSortOrderAndIdNot(pipelineId, 7, environment.getId()))
                .thenReturn(false);
        when(environmentRepository.saveAndFlush(any(DeploymentEnvironmentEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \""
                        + DefaultDeploymentPipelineAdminService.SORT_ORDER_CONSTRAINT + "\""));

        assertThatThrownBy(() -> service.updateEnvironment(pipelineId, orgId, environment.getId(),
                new UpdateDeploymentEnvironmentCommand(null, 7, null, null, null, null, null,
                        null, null, null, null)))
                .isInstanceOf(DeploymentEnvironmentSortOrderConflictException.class);
    }

    private DeploymentPipelineEntity pipeline() {
        var e = new DeploymentPipelineEntity();
        e.setId(pipelineId);
        e.setOrganizationId(orgId);
        e.setName("payments-api");
        e.setProvider(PipelineProvider.GITHUB_ACTIONS);
        return e;
    }

    private DeploymentEnvironmentEntity environment(String name, int sortOrder) {
        var e = new DeploymentEnvironmentEntity();
        e.setId(UUID.randomUUID());
        e.setPipelineId(pipelineId);
        e.setName(name);
        e.setSortOrder(sortOrder);
        return e;
    }

    private static ReviewPlanSnapshot planSnapshot(UUID planId, UUID organizationId) {
        return new ReviewPlanSnapshot(planId, organizationId, true, true, 2, false, 1, List.of(),
                List.of());
    }
}
