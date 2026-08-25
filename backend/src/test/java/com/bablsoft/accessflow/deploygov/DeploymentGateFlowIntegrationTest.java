package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateService;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.ReviewerContext;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewSelfAcknowledgeException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import com.bablsoft.accessflow.deploygov.events.DeploymentOutcomeReportedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentReleasableEvent;
import com.bablsoft.accessflow.deploygov.internal.DefaultDeploymentGateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRollbackReviewRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The #693 machine contract end-to-end against Postgres: trigger → approve → gate releasable →
 * confirm-execution → outcome, plus the deferred-release announcement and the rollback follow-up
 * review. Events are asserted through plain {@code @EventListener}s registered by the test —
 * {@code @ApplicationModuleListener} is AFTER_COMMIT and would silently skip.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentGateFlowIntegrationTest {

    @TestConfiguration
    static class EventProbe {
        final List<DeploymentOutcomeReportedEvent> outcomeEvents = new CopyOnWriteArrayList<>();
        final List<DeploymentReleasableEvent> releasableEvents = new CopyOnWriteArrayList<>();

        @EventListener
        void onOutcome(DeploymentOutcomeReportedEvent event) {
            outcomeEvents.add(event);
        }

        @EventListener
        void onReleasable(DeploymentReleasableEvent event) {
            releasableEvents.add(event);
        }
    }

    @Autowired DeploymentRequestService requestService;
    @Autowired DeploymentReviewService reviewService;
    @Autowired DeploymentGateService gateService;
    @Autowired DeploymentOutcomeService outcomeService;
    @Autowired DeploymentRollbackReviewService rollbackReviewService;
    @Autowired DefaultDeploymentGateService defaultGateService;
    @Autowired DeploymentPipelineAdminService pipelineService;
    @Autowired DeploymentPermissionService permissionService;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired DeploymentReviewDecisionRepository decisionRepository;
    @Autowired DeploymentRollbackReviewRepository rollbackReviewRepository;
    @Autowired DeploymentPipelineUserPermissionRepository userPermissionRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EventProbe probe;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void resetProbe() {
        probe.outcomeEvents.clear();
        probe.releasableEvents.clear();
    }

    @AfterEach
    void cleanup() {
        rollbackReviewRepository.deleteAll();
        decisionRepository.deleteAll();
        requestRepository.deleteAll();
        userPermissionRepository.deleteAll();
        environmentRepository.deleteAll();
        pipelineRepository.deleteAll();
        // Defensive: other integration tests sharing the container may have left group grants and
        // user groups whose real FKs (created_by → users, organization_id → organizations) would
        // block the blanket user/org wipe below.
        jdbcTemplate.update("DELETE FROM deployment_pipeline_group_permissions");
        jdbcTemplate.update("DELETE FROM user_group_memberships");
        jdbcTemplate.update("DELETE FROM user_groups");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void triggerApproveGateConfirmAndReportEndToEnd() {
        var fixture = fixture();
        var requestId = submitToPendingReview(fixture);

        // Not yet releasable: still pending review.
        var pending = gateService.gate(fixture.pipelineName(), "production", "2.4.1",
                fixture.orgId(), fixture.userId(), Set.of());
        assertThat(pending.releasable()).isFalse();
        assertThat(pending.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(pending.grantedApprovals()).isZero();

        // One approval satisfies the environment's quorum of 1.
        var reviewer = saveUser(organizationRepository.findById(fixture.orgId()).orElseThrow());
        reviewService.approve(requestId, new ReviewerContext(reviewer.getId(), fixture.orgId(),
                "ANALYST", Set.of(Permission.DEPLOYMENT_REVIEW)), "lgtm");

        var approved = gateService.gate(fixture.pipelineName(), "production", "2.4.1",
                fixture.orgId(), fixture.userId(), Set.of());
        assertThat(approved.releasable()).isTrue();
        assertThat(approved.status()).isEqualTo(QueryStatus.APPROVED);
        assertThat(approved.requiredApprovals()).isEqualTo(1);
        assertThat(approved.grantedApprovals()).isEqualTo(1);
        assertThat(approved.decisions()).hasSize(1);
        assertThat(approved.frozen()).isFalse();

        // The pipeline confirms it proceeded.
        var executed = gateService.confirmExecution(requestId, fixture.orgId(), fixture.userId(),
                Set.of(), "10.0.0.1");
        assertThat(executed.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(gateService.gateByRequestId(requestId, fixture.orgId(), fixture.userId(),
                Set.of()).releasable()).isFalse();

        // First outcome report lands and publishes; an identical repeat is a 200-style no-op.
        var reported = outcomeService.reportOutcome(requestId, DeploymentOutcome.SUCCEEDED,
                "green", fixture.orgId(), fixture.userId(), Set.of(), "10.0.0.1");
        assertThat(reported.outcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        assertThat(probe.outcomeEvents).hasSize(1);
        assertThat(probe.outcomeEvents.getFirst().outcome())
                .isEqualTo(DeploymentOutcome.SUCCEEDED);

        var repeat = outcomeService.reportOutcome(requestId, DeploymentOutcome.SUCCEEDED, "again",
                fixture.orgId(), fixture.userId(), Set.of(), null);
        assertThat(repeat.outcomeDetail()).isEqualTo("green");
        assertThat(probe.outcomeEvents).hasSize(1);

        // A conflicting outcome is refused.
        assertThatThrownBy(() -> outcomeService.reportOutcome(requestId, DeploymentOutcome.FAILED,
                null, fixture.orgId(), fixture.userId(), Set.of(), null))
                .isInstanceOf(DeploymentOutcomeConflictException.class);
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.EXECUTED);
    }

    @Test
    void deferredApprovedRequestIsAnnouncedOnceItsMomentPasses() {
        var fixture = fixture();
        var request = saveRequest(fixture, QueryStatus.APPROVED);
        request.setScheduledFor(Instant.now().plusSeconds(3600));
        // Rebind after every save — the returned copy carries the bumped @Version.
        request = requestRepository.save(request);

        // Still deferred: the scan skips it and the gate holds it.
        assertThat(requestRepository.findReleasableCandidateIds(Instant.now())).isEmpty();
        assertThat(gateService.gateByRequestId(request.getId(), fixture.orgId(), fixture.userId(),
                Set.of()).releasable()).isFalse();

        request.setScheduledFor(Instant.now().minusSeconds(1));
        request = requestRepository.save(request);

        assertThat(requestRepository.findReleasableCandidateIds(Instant.now()))
                .containsExactly(request.getId());
        assertThat(defaultGateService.markReleasable(request.getId())).isTrue();
        assertThat(probe.releasableEvents).hasSize(1);
        assertThat(probe.releasableEvents.getFirst().deploymentRequestId())
                .isEqualTo(request.getId());

        // One-shot: the stamped row leaves the scan and a second call announces nothing.
        assertThat(requestRepository.findReleasableCandidateIds(Instant.now())).isEmpty();
        assertThat(defaultGateService.markReleasable(request.getId())).isFalse();
        assertThat(probe.releasableEvents).hasSize(1);
    }

    @Test
    void rolledBackOutcomeOpensAFollowUpReviewTheSubmitterCannotAcknowledge() {
        var fixture = fixture();
        var request = saveRequest(fixture, QueryStatus.EXECUTED);

        outcomeService.reportOutcome(request.getId(), DeploymentOutcome.ROLLED_BACK, "regression",
                fixture.orgId(), fixture.userId(), Set.of(), null);

        var review = rollbackReviewRepository.findByDeploymentRequestId(request.getId())
                .orElseThrow();
        assertThat(review.getStatus()).isEqualTo(DeploymentRollbackReviewStatus.PENDING_REVIEW);
        assertThat(review.getSubmittedBy()).isEqualTo(fixture.userId());
        assertThat(review.getOutcomeDetail()).isEqualTo("regression");

        assertThatThrownBy(() -> rollbackReviewService.acknowledge(review.getId(), fixture.orgId(),
                fixture.userId(), "self"))
                .isInstanceOf(DeploymentRollbackReviewSelfAcknowledgeException.class);

        var reviewer = saveUser(organizationRepository.findById(fixture.orgId()).orElseThrow());
        var acknowledged = rollbackReviewService.acknowledge(review.getId(), fixture.orgId(),
                reviewer.getId(), "understood");
        assertThat(acknowledged.status()).isEqualTo(DeploymentRollbackReviewStatus.REVIEWED);
        assertThat(acknowledged.reviewedBy()).isEqualTo(reviewer.getId());

        var listed = rollbackReviewService.list(fixture.orgId(), null,
                new com.bablsoft.accessflow.core.api.PageRequest(0, 20, List.of()));
        assertThat(listed.content()).hasSize(1);

        // FAILED flip on a separate executed request.
        var failed = saveRequest(fixture, QueryStatus.EXECUTED);
        outcomeService.reportOutcome(failed.getId(), DeploymentOutcome.FAILED, "crashed",
                fixture.orgId(), fixture.userId(), Set.of(), null);
        assertThat(requestRepository.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.FAILED);
    }

    /** Submits through the service and waits for the async routing to land in PENDING_REVIEW. */
    private UUID submitToPendingReview(Fixture fixture) {
        var result = requestService.submit(new SubmitDeploymentRequestCommand(fixture.pipelineId(),
                "production", fixture.orgId(), fixture.userId(), false, "2.4.1", "abc123",
                "ghcr.io/app:2.4.1", "https://ci/run/1", "run-" + UUID.randomUUID(),
                Map.of("changelog", "ship"), "ship it", null, null, "10.0.0.1"));
        var requestId = result.request().id();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                        .isEqualTo(QueryStatus.PENDING_REVIEW));
        return requestId;
    }

    private DeploymentRequestEntity saveRequest(Fixture fixture, QueryStatus status) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(fixture.pipelineId());
        entity.setEnvironmentId(fixture.environmentId());
        entity.setOrganizationId(fixture.orgId());
        entity.setSubmittedBy(fixture.userId());
        entity.setVersion("2.4.1");
        entity.setStatus(status);
        entity.setRequiredApprovals(1);
        return requestRepository.save(entity);
    }

    private record Fixture(UUID orgId, UUID userId, UUID pipelineId, UUID environmentId,
                           String pipelineName) {
    }

    private Fixture fixture() {
        var org = saveOrg();
        var user = saveUser(org);
        // AI analysis off, so the analysis listener skips and routing lands in PENDING_REVIEW; the
        // environment requires a single approval and resolves no review plan.
        var pipelineName = "payments-" + UUID.randomUUID();
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                pipelineName, PipelineProvider.GITHUB_ACTIONS, null, null, null, false, null));
        var environment = pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 1, null, false));
        permissionService.grantPermission(pipeline.id(), org.getId(), user.getId(),
                new GrantDeploymentPermissionCommand(user.getId(), true, false, null));
        return new Fixture(org.getId(), user.getId(), pipeline.id(), environment.id(),
                pipelineName);
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
        user.setDisplayName("User");
        user.setPasswordHash("x");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }
}
