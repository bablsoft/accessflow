package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentPipelineVisibilityTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID CALLER_ID = UUID.randomUUID();

    private DeploymentPipelineRepository pipelineRepository;
    private EffectiveDeploymentPermissionResolver permissionResolver;
    private DeploymentPipelineVisibility visibility;
    private DeploymentPipelineEntity pipeline;

    @BeforeEach
    void setUp() {
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        permissionResolver = mock(EffectiveDeploymentPermissionResolver.class);
        visibility = new DeploymentPipelineVisibility(pipelineRepository, permissionResolver);
        pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(ORG_ID);
        when(pipelineRepository.findByIdAndOrganizationId(pipeline.getId(), ORG_ID))
                .thenReturn(Optional.of(pipeline));
        when(permissionResolver.resolve(any(), any())).thenReturn(Optional.empty());
    }

    @ParameterizedTest
    @EnumSource(value = Permission.class,
            names = {"DEPLOYMENT_PIPELINE_MANAGE", "DEPLOYMENT_REVIEW", "QUERY_ADMIN"})
    void eachFunctionalPermissionSeesThePipeline(Permission permission) {
        assertThat(visibility.requireVisible(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(permission))).isSameAs(pipeline);
    }

    @Test
    void anEffectiveTriggerGrantSeesThePipeline() {
        when(permissionResolver.resolve(pipeline.getId(), CALLER_ID)).thenReturn(Optional.of(
                new EffectiveDeploymentPermission(pipeline.getId(), CALLER_ID, true, false, null)));

        assertThat(visibility.requireVisible(pipeline.getId(), ORG_ID, CALLER_ID, Set.of()))
                .isSameAs(pipeline);
    }

    @Test
    void aGrantWithoutTriggerReadsAsNotFound() {
        when(permissionResolver.resolve(pipeline.getId(), CALLER_ID)).thenReturn(Optional.of(
                new EffectiveDeploymentPermission(pipeline.getId(), CALLER_ID, false, true, null)));

        assertThatThrownBy(() -> visibility.requireVisible(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.QUERY_SUBMIT_SELECT)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void nullPermissionsWithoutAGrantReadAsNotFound() {
        assertThatThrownBy(() -> visibility.requireVisible(pipeline.getId(), ORG_ID, CALLER_ID,
                null)).isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void aPipelineInAnotherOrganizationReadsAsNotFound() {
        var otherOrg = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipeline.getId(), otherOrg))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> visibility.requireVisible(pipeline.getId(), otherOrg, CALLER_ID,
                Set.of(Permission.QUERY_ADMIN)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }
}
