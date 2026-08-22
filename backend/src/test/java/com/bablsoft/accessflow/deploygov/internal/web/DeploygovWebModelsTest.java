package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeploygovWebModelsTest {

    @Test
    void createPipelineRequestMapsAllFieldsOntoTheCommand() {
        var orgId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var aiConfigId = UUID.randomUUID();
        var request = new CreateDeploymentPipelineRequest("payments-api",
                PipelineProvider.GITHUB_ACTIONS, "https://git.example/payments", "acme/payments",
                planId, false, aiConfigId);

        var command = request.toCommand(orgId);

        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.name()).isEqualTo("payments-api");
        assertThat(command.provider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
        assertThat(command.repositoryUrl()).isEqualTo("https://git.example/payments");
        assertThat(command.projectRef()).isEqualTo("acme/payments");
        assertThat(command.reviewPlanId()).isEqualTo(planId);
        assertThat(command.aiAnalysisEnabled()).isFalse();
        assertThat(command.aiConfigId()).isEqualTo(aiConfigId);
    }

    @Test
    void updatePipelineRequestPreservesNulls() {
        var command = new UpdateDeploymentPipelineRequest(null, null, null, null, null, null, null,
                null, null, null).toCommand();

        assertThat(command.name()).isNull();
        assertThat(command.provider()).isNull();
        assertThat(command.clearReviewPlan()).isNull();
        assertThat(command.clearAiConfig()).isNull();
        assertThat(command.active()).isNull();
    }

    @Test
    void grantPermissionRequestDefaultsOmittedBooleansToFalse() {
        var userId = UUID.randomUUID();
        var command = new GrantDeploymentPermissionRequest(userId, null, null, null).toCommand();

        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.canTrigger()).isFalse();
        assertThat(command.canBreakGlass()).isFalse();
        assertThat(command.expiresAt()).isNull();
    }

    @Test
    void grantGroupPermissionRequestCarriesTheFlagsWhenSet() {
        var groupId = UUID.randomUUID();
        var expiry = Instant.now();
        var command = new GrantDeploymentGroupPermissionRequest(groupId, true, true, expiry)
                .toCommand();

        assertThat(command.groupId()).isEqualTo(groupId);
        assertThat(command.canTrigger()).isTrue();
        assertThat(command.canBreakGlass()).isTrue();
        assertThat(command.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void updatePermissionRequestsDefaultOmittedBooleansToFalse() {
        assertThat(new UpdateDeploymentPermissionRequest(null, true, null).toCommand().canTrigger())
                .isFalse();
        assertThat(new UpdateDeploymentGroupPermissionRequest(true, null, null).toCommand()
                .canBreakGlass()).isFalse();
    }

    @Test
    void environmentRequestsMapOntoCommands() {
        var planId = UUID.randomUUID();
        var create = new CreateDeploymentEnvironmentRequest("production", 2, false, 3, planId,
                true).toCommand();
        assertThat(create.name()).isEqualTo("production");
        assertThat(create.sortOrder()).isEqualTo(2);
        assertThat(create.requireReview()).isFalse();
        assertThat(create.requiredApprovals()).isEqualTo(3);
        assertThat(create.reviewPlanId()).isEqualTo(planId);
        assertThat(create.allowBreakGlass()).isTrue();

        var update = new UpdateDeploymentEnvironmentRequest(null, null, null, null, true, null,
                true, null).toCommand();
        assertThat(update.clearRequiredApprovals()).isTrue();
        assertThat(update.clearReviewPlan()).isTrue();
        assertThat(update.name()).isNull();
    }

    @Test
    void freezeWindowRequestMapsOntoTheCommandWithCallersOrganization() {
        var orgId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var command = new DeploymentFreezeWindowRequest(pipelineId, null, null, null,
                List.of(5, 6), LocalTime.of(18, 0), LocalTime.of(22, 0), "Europe/Berlin",
                FreezeBehavior.REJECT, "weekend freeze", false).toCommand(orgId);

        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.pipelineId()).isEqualTo(pipelineId);
        assertThat(command.daysOfWeek()).containsExactly(5, 6);
        assertThat(command.behavior()).isEqualTo(FreezeBehavior.REJECT);
        assertThat(command.enabled()).isFalse();
    }

    @Test
    void pipelinePageResponseCopiesPaginationMetadata() {
        var view = new DeploymentPipelineView(UUID.randomUUID(), UUID.randomUUID(), "p",
                PipelineProvider.GENERIC, null, null, null, true, null, true, Instant.now(),
                Instant.now());
        var page = DeploymentPipelinePageResponse.from(
                new PageResponse<>(List.of(view), 1, 20, 41, 3));

        assertThat(page.content()).hasSize(1);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void freezeWindowPageResponseMapsContent() {
        var view = new DeploymentFreezeWindowView(UUID.randomUUID(), UUID.randomUUID(), null, null,
                Instant.now(), Instant.now().plusSeconds(60), null, null, null, null,
                FreezeBehavior.HOLD, null, true, Instant.now());
        var page = DeploymentFreezeWindowPageResponse.from(
                new PageResponse<>(List.of(view), 0, 20, 1, 1));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).behavior()).isEqualTo(FreezeBehavior.HOLD);
    }
}
