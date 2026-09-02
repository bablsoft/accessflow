package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowView;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateView;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewDecisionView;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyView;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.List;
import java.util.Set;
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
                true, List.of("acme", "eu")).toCommand();
        assertThat(create.name()).isEqualTo("production");
        assertThat(create.sortOrder()).isEqualTo(2);
        assertThat(create.requireReview()).isFalse();
        assertThat(create.requiredApprovals()).isEqualTo(3);
        assertThat(create.reviewPlanId()).isEqualTo(planId);
        assertThat(create.allowBreakGlass()).isTrue();
        assertThat(create.tags()).containsExactly("acme", "eu");

        var update = new UpdateDeploymentEnvironmentRequest(null, null, null, null, true, null,
                true, null, null).toCommand();
        assertThat(update.clearRequiredApprovals()).isTrue();
        assertThat(update.clearReviewPlan()).isTrue();
        assertThat(update.name()).isNull();
        // Null tags must survive the mapping untouched — it is the "leave unchanged" signal.
        assertThat(update.tags()).isNull();
    }

    @Test
    void environmentResponseCopiesTheViewIncludingTags() {
        var view = new DeploymentEnvironmentView(
                UUID.randomUUID(), UUID.randomUUID(), "production", 1, true, 2, null, false,
                Instant.now(), List.of("acme"));
        var response = DeploymentEnvironmentResponse.from(view);

        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.pipelineId()).isEqualTo(view.pipelineId());
        assertThat(response.name()).isEqualTo("production");
        assertThat(response.sortOrder()).isEqualTo(1);
        assertThat(response.requireReview()).isTrue();
        assertThat(response.requiredApprovals()).isEqualTo(2);
        assertThat(response.reviewPlanId()).isNull();
        assertThat(response.allowBreakGlass()).isFalse();
        assertThat(response.createdAt()).isEqualTo(view.createdAt());
        assertThat(response.tags()).containsExactly("acme");
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

    @Test
    void submitDeploymentRequestMapsAllFieldsOntoTheCommand() {
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var scheduledFor = Instant.parse("2026-09-01T00:00:00Z");
        var request = new SubmitDeploymentRequestRequest(pipelineId, "production", "2.4.1", "abc123",
                "ghcr.io/app:2.4.1", "https://ci/run/1", "run-1", Map.of("changelog", "fix"),
                "ship it", scheduledFor, null);

        var command = request.toCommand(orgId, userId, true, "10.0.0.1");

        assertThat(command.pipelineId()).isEqualTo(pipelineId);
        assertThat(command.environment()).isEqualTo("production");
        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.submitterUserId()).isEqualTo(userId);
        assertThat(command.admin()).isTrue();
        assertThat(command.version()).isEqualTo("2.4.1");
        assertThat(command.commitSha()).isEqualTo("abc123");
        assertThat(command.artifactRef()).isEqualTo("ghcr.io/app:2.4.1");
        assertThat(command.runUrl()).isEqualTo("https://ci/run/1");
        assertThat(command.externalRunId()).isEqualTo("run-1");
        assertThat(command.metadata()).containsEntry("changelog", "fix");
        assertThat(command.justification()).isEqualTo("ship it");
        assertThat(command.scheduledFor()).isEqualTo(scheduledFor);
        assertThat(command.submittedIp()).isEqualTo("10.0.0.1");
        // An absent breakGlass flag carries no submission reason (#692).
        assertThat(command.submissionReason()).isNull();
    }

    @Test
    void submitDeploymentRequestDefaultsMetadataToAnEmptyMap() {
        var command = new SubmitDeploymentRequestRequest(UUID.randomUUID(), "production", "2.4.1",
                null, null, null, null, null, null, null, null)
                .toCommand(UUID.randomUUID(), UUID.randomUUID(), false, null);

        assertThat(command.metadata()).isEmpty();
    }

    @Test
    void submitRequestBreakGlassMapsToEmergencyAccess() {
        var command = new SubmitDeploymentRequestRequest(UUID.randomUUID(), "production", "2.4.1",
                null, null, null, null, null, null, null, true)
                .toCommand(UUID.randomUUID(), UUID.randomUUID(), false, null);

        assertThat(command.submissionReason()).isEqualTo(SubmissionReason.EMERGENCY_ACCESS);
    }

    @Test
    void submitRequestBreakGlassFalseCarriesNoSubmissionReason() {
        var command = new SubmitDeploymentRequestRequest(UUID.randomUUID(), "production", "2.4.1",
                null, null, null, null, null, null, null, false)
                .toCommand(UUID.randomUUID(), UUID.randomUUID(), false, null);

        assertThat(command.submissionReason()).isNull();
    }

    @Test
    void breakGlassMayNotBeCombinedWithASchedule() {
        var scheduledFor = Instant.parse("2026-09-01T00:00:00Z");
        var pipelineId = UUID.randomUUID();

        assertThat(new SubmitDeploymentRequestRequest(pipelineId, "production", "2.4.1", null,
                null, null, null, null, null, scheduledFor, true).isBreakGlassUnscheduled())
                .isFalse();
        assertThat(new SubmitDeploymentRequestRequest(pipelineId, "production", "2.4.1", null,
                null, null, null, null, null, scheduledFor, null).isBreakGlassUnscheduled())
                .isTrue();
        assertThat(new SubmitDeploymentRequestRequest(pipelineId, "production", "2.4.1", null,
                null, null, null, null, null, scheduledFor, false).isBreakGlassUnscheduled())
                .isTrue();
        assertThat(new SubmitDeploymentRequestRequest(pipelineId, "production", "2.4.1", null,
                null, null, null, null, null, null, true).isBreakGlassUnscheduled())
                .isTrue();
    }

    @Test
    void deploymentRequestResponseMapsTheViewIncludingDecisions() {
        var decision = new DeploymentReviewDecisionView(UUID.randomUUID(), UUID.randomUUID(),
                DecisionType.APPROVED, "lgtm", 1, Instant.parse("2026-08-21T18:00:00Z"));
        var view = new DeploymentRequestView(UUID.randomUUID(), UUID.randomUUID(), "payments-api",
                PipelineProvider.GITHUB_ACTIONS, UUID.randomUUID(), "production", UUID.randomUUID(),
                "ci@example.com", "2.4.1", "abc123", "ghcr.io/app:2.4.1", "https://ci/run/1",
                "run-1", Map.of("changelog", "fix"), QueryStatus.APPROVED,
                SubmissionReason.USER_SUBMITTED, "ship it", UUID.randomUUID(), RiskLevel.HIGH, 80,
                "schema migration", 2, null, DeploymentOutcome.SUCCEEDED,
                Instant.parse("2026-08-21T19:00:00Z"), "deployed", Instant.now(), List.of(decision));

        var response = DeploymentRequestResponse.from(view);

        assertThat(response.pipelineName()).isEqualTo("payments-api");
        assertThat(response.provider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
        assertThat(response.environmentName()).isEqualTo("production");
        assertThat(response.submittedByEmail()).isEqualTo("ci@example.com");
        assertThat(response.metadata()).containsEntry("changelog", "fix");
        assertThat(response.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(response.aiRiskScore()).isEqualTo(80);
        assertThat(response.requiredApprovals()).isEqualTo(2);
        assertThat(response.outcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        assertThat(response.outcomeDetail()).isEqualTo("deployed");
        assertThat(response.decisions()).hasSize(1);
        assertThat(response.decisions().get(0).decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(response.decisions().get(0).comment()).isEqualTo("lgtm");
    }

    @Test
    void deploymentRequestPageResponseMapsContent() {
        var view = new DeploymentRequestView(UUID.randomUUID(), UUID.randomUUID(), "p",
                PipelineProvider.GENERIC, UUID.randomUUID(), "staging", UUID.randomUUID(), null,
                "1.0.0", null, null, null, null, null, QueryStatus.PENDING_AI,
                SubmissionReason.USER_SUBMITTED, null, null, null, null, null, 1, null, null, null,
                null, Instant.now(), null);

        var page = DeploymentRequestPageResponse.from(new PageResponse<>(List.of(view), 2, 20, 45, 3));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).decisions()).isEmpty();
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(45);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void createRoutingPolicyRequestDefaultsPriorityEnabledAndConditions() {
        var orgId = UUID.randomUUID();

        var command = new CreateDeploymentRoutingPolicyRequest(null, "p", null,
                DeploymentRoutingAction.AUTO_REJECT, null, null, null).toCommand(orgId);

        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.conditions()).isEqualTo(DeploymentRoutingConditions.NONE);
        assertThat(command.priority()).isEqualTo(100);
        assertThat(command.enabled()).isTrue();
    }

    @Test
    void createRoutingPolicyRequestCarriesEveryCondition() {
        var pipelineId = UUID.randomUUID();
        var conditions = new DeploymentRoutingConditionsRequest(List.of("production"),
                List.of("GITHUB_ACTIONS"), RiskLevel.HIGH, List.of("2.*"), Set.of(5),
                LocalTime.of(16, 0), LocalTime.of(23, 0), "Europe/Berlin");

        var command = new CreateDeploymentRoutingPolicyRequest(pipelineId, "p", conditions,
                DeploymentRoutingAction.ESCALATE, 2, 10, false).toCommand(UUID.randomUUID());

        assertThat(command.pipelineId()).isEqualTo(pipelineId);
        assertThat(command.conditions().environments()).containsExactly("production");
        assertThat(command.conditions().providers()).containsExactly("GITHUB_ACTIONS");
        assertThat(command.conditions().minRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(command.conditions().versionGlobs()).containsExactly("2.*");
        assertThat(command.conditions().daysOfWeek()).containsExactly(5);
        assertThat(command.conditions().startTime()).isEqualTo(LocalTime.of(16, 0));
        assertThat(command.conditions().endTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(command.conditions().timezone()).isEqualTo("Europe/Berlin");
        assertThat(command.requiredApprovals()).isEqualTo(2);
        assertThat(command.priority()).isEqualTo(10);
        assertThat(command.enabled()).isFalse();
    }

    @Test
    void updateRoutingPolicyRequestLeavesAbsentConditionsNull() {
        var command = new UpdateDeploymentRoutingPolicyRequest(null, true, "renamed", null, null,
                null, 20, false).toCommand();

        assertThat(command.clearPipeline()).isTrue();
        assertThat(command.name()).isEqualTo("renamed");
        assertThat(command.conditions()).isNull();
        assertThat(command.priority()).isEqualTo(20);
        assertThat(command.enabled()).isFalse();
    }

    @Test
    void routingPolicyResponseMapsTheViewAndItsConditions() {
        var conditions = new DeploymentRoutingConditions(List.of("production"), null,
                RiskLevel.CRITICAL, null, null, null, null, null);
        var view = new DeploymentRoutingPolicyView(UUID.randomUUID(), UUID.randomUUID(), null,
                "freeze fridays", conditions, DeploymentRoutingAction.AUTO_REJECT, null, 10, true,
                Instant.now());

        var response = DeploymentRoutingPolicyResponse.from(view);

        assertThat(response.name()).isEqualTo("freeze fridays");
        assertThat(response.action()).isEqualTo(DeploymentRoutingAction.AUTO_REJECT);
        assertThat(response.priority()).isEqualTo(10);
        assertThat(response.enabled()).isTrue();
        assertThat(response.conditions().environments()).containsExactly("production");
        assertThat(response.conditions().minRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void routingConditionsRequestFromNullIsNull() {
        assertThat(DeploymentRoutingConditionsRequest.from(null)).isNull();
    }

    @Test
    void submitRequestToleratesNullMetadataValues() {
        var metadata = new java.util.HashMap<String, Object>();
        metadata.put("changelog", null);

        var command = new SubmitDeploymentRequestRequest(UUID.randomUUID(), "production", "2.4.1",
                null, null, null, null, metadata, null, null, null)
                .toCommand(UUID.randomUUID(), UUID.randomUUID(), false, null);

        assertThat(command.metadata()).containsEntry("changelog", null);
    }

    @Test
    void routingConditionsDropNullAndBlankEntries() {
        var conditions = new DeploymentRoutingConditionsRequest(
                java.util.Arrays.asList("production", null, "  "), null, null,
                java.util.Arrays.asList((String) null), java.util.Collections.singleton(null), null,
                null, null).toConditions();

        assertThat(conditions.environments()).containsExactly("production");
        assertThat(conditions.versionGlobs()).isEmpty();
        assertThat(conditions.daysOfWeek()).isEmpty();
    }

    @Test
    void gateResponseNestsApprovalsAndMapsDecisions() {
        var requestId = UUID.randomUUID();
        var decision = new DeploymentReviewDecisionView(UUID.randomUUID(), UUID.randomUUID(),
                DecisionType.APPROVED, "lgtm", 1, Instant.parse("2026-08-24T11:00:00Z"));
        var view = new DeploymentGateView(requestId, QueryStatus.APPROVED, true, 2, 1,
                List.of(decision), true, "change freeze",
                Instant.parse("2026-08-24T12:00:00Z"), RiskLevel.LOW);

        var response = DeploymentGateResponse.from(view);

        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.releasable()).isTrue();
        assertThat(response.approvals().required()).isEqualTo(2);
        assertThat(response.approvals().granted()).isEqualTo(1);
        assertThat(response.decisions()).hasSize(1);
        assertThat(response.decisions().getFirst().decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(response.frozen()).isTrue();
        assertThat(response.freezeReason()).isEqualTo("change freeze");
        assertThat(response.aiRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void gateViewDefendsItsDecisionsList() {
        var view = new DeploymentGateView(UUID.randomUUID(), QueryStatus.APPROVED, false, 1, 0,
                null, false, null, null, null);

        assertThat(view.decisions()).isEmpty();
    }

    @Test
    void rollbackReviewResponseMapsAllFields() {
        var viewId = UUID.randomUUID();
        var view = new com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewView(viewId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "detail",
                com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus.REVIEWED,
                UUID.randomUUID(), "ack", Instant.parse("2026-08-24T12:00:00Z"),
                Instant.parse("2026-08-24T11:00:00Z"));

        var response = DeploymentRollbackReviewResponse.from(view);

        assertThat(response.id()).isEqualTo(viewId);
        assertThat(response.status()).isEqualTo(
                com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus.REVIEWED);
        assertThat(response.reviewComment()).isEqualTo("ack");
    }

    @Test
    void rollbackReviewPageResponseCopiesPaginationMetadata() {
        var view = new com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null,
                com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus.PENDING_REVIEW,
                null, null, null, Instant.parse("2026-08-24T11:00:00Z"));

        var page = DeploymentRollbackReviewPageResponse.from(
                new PageResponse<>(List.of(view), 1, 20, 21, 2));

        assertThat(page.content()).hasSize(1);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- version inventory (#742)

    @Test
    void environmentVersionResponseNestsTheEnvironmentAndDrift() {
        var pipelineId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var deployedAt = Instant.parse("2026-08-26T12:00:00Z");
        var latestAt = Instant.parse("2026-08-30T12:00:00Z");
        var view = new com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionView(
                pipelineId, "payments-api", environmentId, "prod-acme", List.of("prod", "acme"), 3,
                "2.4.0", requestId, deployedAt, "2.3.9", DeploymentOutcome.SUCCEEDED,
                new com.bablsoft.accessflow.deploygov.api.DeploymentVersionDriftView(
                        "2.4.1", latestAt, true, 4L, 1L));

        var response = DeploymentEnvironmentVersionResponse.from(view);

        assertThat(response.pipelineId()).isEqualTo(pipelineId);
        assertThat(response.pipelineName()).isEqualTo("payments-api");
        assertThat(response.environment().id()).isEqualTo(environmentId);
        assertThat(response.environment().name()).isEqualTo("prod-acme");
        assertThat(response.environment().tags()).containsExactly("prod", "acme");
        assertThat(response.environment().sortOrder()).isEqualTo(3);
        assertThat(response.currentVersion()).isEqualTo("2.4.0");
        assertThat(response.currentRequestId()).isEqualTo(requestId);
        assertThat(response.deployedAt()).isEqualTo(deployedAt);
        assertThat(response.previousVersion()).isEqualTo("2.3.9");
        assertThat(response.lastOutcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        assertThat(response.drift().latestVersion()).isEqualTo("2.4.1");
        assertThat(response.drift().latestDeployedAt()).isEqualTo(latestAt);
        assertThat(response.drift().drifted()).isTrue();
        assertThat(response.drift().daysBehind()).isEqualTo(4L);
        assertThat(response.drift().deploymentsBehind()).isEqualTo(1L);
    }

    @Test
    void environmentVersionResponseCarriesNullVersionFieldsForANeverDeployedEnvironment() {
        var view = new com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionView(
                UUID.randomUUID(), "payments-api", UUID.randomUUID(), "staging", null, 0,
                null, null, null, null, null,
                new com.bablsoft.accessflow.deploygov.api.DeploymentVersionDriftView(
                        "2.4.1", Instant.parse("2026-08-30T12:00:00Z"), true, null, null));

        var response = DeploymentEnvironmentVersionResponse.from(view);

        assertThat(response.environment().tags()).isEmpty();
        assertThat(response.currentVersion()).isNull();
        assertThat(response.currentRequestId()).isNull();
        assertThat(response.deployedAt()).isNull();
        assertThat(response.previousVersion()).isNull();
        assertThat(response.lastOutcome()).isNull();
        assertThat(response.drift().daysBehind()).isNull();
        assertThat(response.drift().deploymentsBehind()).isNull();
    }

    @Test
    void environmentVersionPageResponseCopiesPaginationMetadata() {
        var view = new com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionView(
                UUID.randomUUID(), "payments-api", UUID.randomUUID(), "prod", List.of(), 0,
                "2.4.1", UUID.randomUUID(), Instant.parse("2026-08-30T12:00:00Z"), null, null,
                new com.bablsoft.accessflow.deploygov.api.DeploymentVersionDriftView(
                        "2.4.1", Instant.parse("2026-08-30T12:00:00Z"), false, 0L, 0L));

        var page = DeploymentEnvironmentVersionPageResponse.from(
                new PageResponse<>(List.of(view), 1, 10, 11, 2));

        assertThat(page.content()).hasSize(1);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(11);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void historyEntryResponseMapsAllFields() {
        var requestId = UUID.randomUUID();
        var submittedBy = UUID.randomUUID();
        var createdAt = Instant.parse("2026-08-30T11:00:00Z");
        var executedAt = Instant.parse("2026-08-30T12:00:00Z");
        var view = new com.bablsoft.accessflow.deploygov.api.DeploymentVersionHistoryEntryView(
                requestId, "2.4.1", QueryStatus.EXECUTED, DeploymentOutcome.SUCCEEDED, executedAt,
                submittedBy, SubmissionReason.USER_SUBMITTED, "abc123",
                "https://ci.example.com/run/1", createdAt, executedAt);

        var response = DeploymentVersionHistoryEntryResponse.from(view);

        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.version()).isEqualTo("2.4.1");
        assertThat(response.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(response.outcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        assertThat(response.outcomeReportedAt()).isEqualTo(executedAt);
        assertThat(response.submittedBy()).isEqualTo(submittedBy);
        assertThat(response.submissionReason()).isEqualTo(SubmissionReason.USER_SUBMITTED);
        assertThat(response.commitSha()).isEqualTo("abc123");
        assertThat(response.runUrl()).isEqualTo("https://ci.example.com/run/1");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.executedAt()).isEqualTo(executedAt);
    }

    @Test
    void historyPageResponseCopiesPaginationMetadata() {
        var view = new com.bablsoft.accessflow.deploygov.api.DeploymentVersionHistoryEntryView(
                UUID.randomUUID(), "2.4.1", QueryStatus.REJECTED, null, null, UUID.randomUUID(),
                SubmissionReason.USER_SUBMITTED, null, null,
                Instant.parse("2026-08-30T11:00:00Z"), null);

        var page = DeploymentVersionHistoryPageResponse.from(
                new PageResponse<>(List.of(view), 0, 20, 1, 1));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().outcome()).isNull();
        assertThat(page.content().getFirst().executedAt()).isNull();
        assertThat(page.totalElements()).isEqualTo(1);
    }
}
