package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentRoutingPolicyCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingConditions;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyPriorityConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyService;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRoutingPolicyRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the #691 submission half end-to-end against Postgres: trigger → analysis-skipped →
 * routed-to-review, the idempotent replay, the REJECT freeze window, the list filters, and cancel.
 * The pipelines here have {@code aiAnalysisEnabled = false}, so the analysis listener takes its
 * skip path and no AI provider is involved.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentSubmissionIntegrationTest {

    @Autowired DeploymentRequestService requestService;
    @Autowired DeploymentPipelineAdminService pipelineService;
    @Autowired DeploymentPermissionService permissionService;
    @Autowired DeploymentFreezeWindowService freezeWindowService;
    @Autowired DeploymentRoutingPolicyService routingPolicyService;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired DeploymentRoutingPolicyRepository routingPolicyRepository;
    @Autowired DeploymentPipelineUserPermissionRepository userPermissionRepository;
    @Autowired DeploymentFreezeWindowRepository freezeWindowRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;

    // Rows are committed into the shared Testcontainers DB; clear them children-first so a later
    // integration test's userRepository.deleteAll() does not trip an FK.
    @AfterEach
    void cleanup() {
        requestRepository.deleteAll();
        routingPolicyRepository.deleteAll();
        userPermissionRepository.deleteAll();
        freezeWindowRepository.deleteAll();
        environmentRepository.deleteAll();
        pipelineRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void triggerFlowsThroughSkippedAnalysisToReview() {
        var fixture = fixture();

        var result = requestService.submit(command(fixture, "run-1"));

        assertThat(result.replay()).isFalse();
        assertThat(result.request().status()).isEqualTo(QueryStatus.PENDING_AI);
        // The analysis listener and the state machine are @ApplicationModuleListeners, so the
        // transition lands after this transaction commits.
        awaitStatus(result.request().id(), QueryStatus.PENDING_REVIEW);

        var view = requestService.get(result.request().id(), fixture.orgId(), fixture.userId(),
                Set.of());
        assertThat(view.environmentName()).isEqualTo("production");
        assertThat(view.pipelineName()).isEqualTo(fixture.pipelineName());
        assertThat(view.version()).isEqualTo("2.4.1");
        assertThat(view.metadata()).containsEntry("changelog", "fix things");
        assertThat(view.requiredApprovals()).isEqualTo(2);
        assertThat(view.aiAnalysisId()).isNull();
        assertThat(view.decisions()).isEmpty();
    }

    @Test
    void aRoutingPolicyAutoApprovesBeforeTheEnvironmentPolicyIsConsulted() {
        var fixture = fixture();
        routingPolicyService.create(new CreateDeploymentRoutingPolicyCommand(fixture.orgId(),
                fixture.pipelineId(), "auto-approve production",
                new DeploymentRoutingConditions(List.of("production"), null, null, List.of("2.*"),
                        null, null, null, null),
                DeploymentRoutingAction.AUTO_APPROVE, null, 10, true));

        var result = requestService.submit(command(fixture, "run-routed"));

        awaitStatus(result.request().id(), QueryStatus.APPROVED);
    }

    @Test
    void aRejectFreezeWindowAutoRejectsAtSubmission() {
        var fixture = fixture();
        var now = Instant.now();
        freezeWindowService.create(new DeploymentFreezeWindowCommand(fixture.orgId(),
                fixture.pipelineId(), null, now.minus(1, ChronoUnit.HOURS),
                now.plus(1, ChronoUnit.HOURS), null, null, null, null, FreezeBehavior.REJECT,
                "code freeze", true));

        var result = requestService.submit(command(fixture, "run-frozen"));

        assertThat(result.request().status()).isEqualTo(QueryStatus.REJECTED);
        assertThat(requestRepository.findById(result.request().id()).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.REJECTED);
    }

    @Test
    void aHoldFreezeWindowDoesNotBlockSubmission() {
        var fixture = fixture();
        var now = Instant.now();
        freezeWindowService.create(new DeploymentFreezeWindowCommand(fixture.orgId(),
                fixture.pipelineId(), null, now.minus(1, ChronoUnit.HOURS),
                now.plus(1, ChronoUnit.HOURS), null, null, null, null, FreezeBehavior.HOLD,
                "release train", true));

        var result = requestService.submit(command(fixture, "run-held"));

        assertThat(result.request().status()).isEqualTo(QueryStatus.PENDING_AI);
        awaitStatus(result.request().id(), QueryStatus.PENDING_REVIEW);
    }

    @Test
    void aReplayedTriggerReturnsTheSameRequest() {
        var fixture = fixture();

        var first = requestService.submit(command(fixture, "run-replay"));
        var second = requestService.submit(command(fixture, "run-replay"));

        assertThat(second.replay()).isTrue();
        assertThat(second.request().id()).isEqualTo(first.request().id());
        assertThat(requestRepository.count()).isEqualTo(1);
    }

    @Test
    void aTriggerWithoutAGrantIsRejected() {
        var fixture = fixture();
        userPermissionRepository.deleteAll();

        assertThatThrownBy(() -> requestService.submit(command(fixture, "run-denied")))
                .isInstanceOf(DeploymentRequestPermissionException.class);
        assertThat(requestRepository.count()).isZero();
    }

    @Test
    void listFiltersOnStatusPipelineEnvironmentAndVersion() {
        var fixture = fixture();
        var submitted = requestService.submit(command(fixture, "run-list"));
        awaitStatus(submitted.request().id(), QueryStatus.PENDING_REVIEW);

        var matching = requestService.list(new DeploymentRequestListFilter(fixture.orgId(),
                fixture.userId(), fixture.pipelineId(), "PRODUCTION", "2.4.1",
                QueryStatus.PENDING_REVIEW, null, null), PageRequest.of(0, 20));
        assertThat(matching.content()).extracting(v -> v.id())
                .containsExactly(submitted.request().id());

        var wrongEnvironment = requestService.list(new DeploymentRequestListFilter(fixture.orgId(),
                null, null, "staging", null, null, null, null), PageRequest.of(0, 20));
        assertThat(wrongEnvironment.content()).isEmpty();

        var wrongVersion = requestService.list(new DeploymentRequestListFilter(fixture.orgId(),
                null, null, null, "9.9.9", null, null, null), PageRequest.of(0, 20));
        assertThat(wrongVersion.content()).isEmpty();
    }

    @Test
    void theSubmitterCanCancelARequestAwaitingReview() {
        var fixture = fixture();
        var submitted = requestService.submit(command(fixture, "run-cancel"));
        awaitStatus(submitted.request().id(), QueryStatus.PENDING_REVIEW);

        requestService.cancel(submitted.request().id(), fixture.orgId(), fixture.userId());

        assertThat(requestRepository.findById(submitted.request().id()).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.CANCELLED);
    }

    @Test
    void routingPolicyPriorityIsUniquePerOrganization() {
        var fixture = fixture();
        routingPolicyService.create(new CreateDeploymentRoutingPolicyCommand(fixture.orgId(), null,
                "first", DeploymentRoutingConditions.NONE, DeploymentRoutingAction.AUTO_REJECT,
                null, 42, true));

        assertThatThrownBy(() -> routingPolicyService.create(new CreateDeploymentRoutingPolicyCommand(
                fixture.orgId(), null, "second", DeploymentRoutingConditions.NONE,
                DeploymentRoutingAction.AUTO_APPROVE, null, 42, true)))
                .isInstanceOf(DeploymentRoutingPolicyPriorityConflictException.class);
    }

    private void awaitStatus(UUID requestId, QueryStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                        .isEqualTo(expected));
    }

    private SubmitDeploymentRequestCommand command(Fixture fixture, String externalRunId) {
        return new SubmitDeploymentRequestCommand(fixture.pipelineId(), "production",
                fixture.orgId(), fixture.userId(), false, "2.4.1", "abc123", "ghcr.io/app:2.4.1",
                "https://ci/run/1", externalRunId, Map.of("changelog", "fix things"), "ship it",
                null, null, "10.0.0.1");
    }

    private record Fixture(UUID orgId, UUID userId, UUID pipelineId, String pipelineName) {
    }

    private Fixture fixture() {
        var org = saveOrg();
        var user = saveUser(org);
        var pipelineName = "payments-" + UUID.randomUUID();
        // AI analysis off, so the listener publishes DeploymentAnalysisSkippedEvent and no provider
        // is involved; the environment requires review with two approvals.
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                pipelineName, PipelineProvider.GITHUB_ACTIONS, null, null, null, false, null));
        pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 2, null, false, null));
        permissionService.grantPermission(pipeline.id(), org.getId(), user.getId(),
                new GrantDeploymentPermissionCommand(user.getId(), true, false, null));
        return new Fixture(org.getId(), user.getId(), pipeline.id(), pipelineName);
    }

    private OrganizationEntity saveOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org-" + UUID.randomUUID());
        org.setSlug("org-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setDisplayName("CI Runner");
        user.setPasswordHash("x");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }
}
