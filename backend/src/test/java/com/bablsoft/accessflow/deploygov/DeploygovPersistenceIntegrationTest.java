package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineGroupPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentReviewDecisionEntity;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRollbackReviewEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRoutingPolicyEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentVersionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineGroupPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRollbackReviewRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRoutingPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that the V149–V156 migrations apply and every deploygov JPA entity maps to its table —
 * entity ↔ DDL parity under {@code ddl-auto=validate}, including the pg enum
 * {@code columnDefinition}s ({@code pipeline_provider}, {@code freeze_behavior},
 * {@code deployment_outcome}, and the shared {@code query_status} / {@code decision} /
 * {@code api_routing_action}), the jsonb / {@code smallint[]} / {@code text[]} / {@code TIME}
 * columns, the partial unique trigger-idempotency index, and the one-row-per-environment
 * version-tracking table (#741) — booting the full application context so the new module wires
 * cleanly.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploygovPersistenceIntegrationTest {

    @Autowired
    private DeploymentPipelineRepository pipelineRepository;
    @Autowired
    private DeploymentEnvironmentRepository environmentRepository;
    @Autowired
    private DeploymentFreezeWindowRepository freezeWindowRepository;
    @Autowired
    private DeploymentRequestRepository requestRepository;
    @Autowired
    private DeploymentReviewDecisionRepository decisionRepository;
    @Autowired
    private DeploymentRoutingPolicyRepository routingPolicyRepository;
    @Autowired
    private DeploymentPipelineUserPermissionRepository userPermissionRepository;
    @Autowired
    private DeploymentPipelineGroupPermissionRepository groupPermissionRepository;
    @Autowired
    private DeploymentRollbackReviewRepository rollbackReviewRepository;
    @Autowired
    private DeploymentEnvironmentVersionRepository environmentVersionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private DeploymentPipelineEntity newPipeline() {
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(UUID.randomUUID());
        pipeline.setName("payments-api-" + UUID.randomUUID());
        pipeline.setProvider(PipelineProvider.GITHUB_ACTIONS);
        pipeline.setRepositoryUrl("https://github.com/acme/payments-api");
        pipeline.setProjectRef("acme/payments-api");
        return pipelineRepository.saveAndFlush(pipeline);
    }

    private DeploymentEnvironmentEntity newEnvironment(UUID pipelineId, String name) {
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(UUID.randomUUID());
        environment.setPipelineId(pipelineId);
        environment.setName(name);
        environment.setSortOrder(1);
        return environmentRepository.saveAndFlush(environment);
    }

    private DeploymentRequestEntity newRequest(UUID pipelineId, UUID environmentId, String version,
                                               String externalRunId) {
        var request = new DeploymentRequestEntity();
        request.setId(UUID.randomUUID());
        request.setPipelineId(pipelineId);
        request.setEnvironmentId(environmentId);
        request.setOrganizationId(UUID.randomUUID());
        request.setSubmittedBy(UUID.randomUUID());
        request.setVersion(version);
        request.setExternalRunId(externalRunId);
        return request;
    }

    @Test
    void persistsAndReloadsPipelineWithEnvironment() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "production");
        environment.setRequiredApprovals(2);
        environment.setAllowBreakGlass(true);
        environmentRepository.saveAndFlush(environment);

        var reloadedPipeline = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(reloadedPipeline.getProvider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
        assertThat(reloadedPipeline.isAiAnalysisEnabled()).isTrue();
        assertThat(reloadedPipeline.isActive()).isTrue();

        var reloadedEnvironment = environmentRepository.findById(environment.getId()).orElseThrow();
        assertThat(reloadedEnvironment.getPipelineId()).isEqualTo(pipeline.getId());
        assertThat(reloadedEnvironment.getRequiredApprovals()).isEqualTo(2);
        assertThat(reloadedEnvironment.isRequireReview()).isTrue();
        assertThat(reloadedEnvironment.isAllowBreakGlass()).isTrue();
    }

    @Test
    void environmentTagsRoundTripAsATextArray() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "prod-acme");
        environment.setTags(new String[]{"acme", "eu"});
        environmentRepository.saveAndFlush(environment);

        var reloaded = environmentRepository.findById(environment.getId()).orElseThrow();
        assertThat(reloaded.getTags()).containsExactly("acme", "eu");

        var untagged = environmentRepository
                .findById(newEnvironment(pipeline.getId(), "staging").getId()).orElseThrow();
        assertThat(untagged.getTags()).isEmpty();
    }

    @Test
    void persistsAndReloadsEnvironmentVersionRow() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "production");

        var row = new DeploymentEnvironmentVersionEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(pipeline.getOrganizationId());
        row.setPipelineId(pipeline.getId());
        row.setEnvironmentId(environment.getId());
        row.setCurrentVersion("2.4.1");
        row.setCurrentRequestId(UUID.randomUUID());
        row.setDeployedAt(Instant.parse("2026-08-24T12:00:00Z"));
        row.setPreviousVersion("2.4.0");
        row.setPreviousRequestId(UUID.randomUUID());
        row.setPreviousDeployedAt(Instant.parse("2026-08-20T09:00:00Z"));
        row.setLastOutcome(DeploymentOutcome.SUCCEEDED);
        environmentVersionRepository.saveAndFlush(row);

        var reloaded = environmentVersionRepository.findByEnvironmentId(environment.getId())
                .orElseThrow();
        assertThat(reloaded.getCurrentVersion()).isEqualTo("2.4.1");
        assertThat(reloaded.getPreviousVersion()).isEqualTo("2.4.0");
        assertThat(reloaded.getLastOutcome()).isEqualTo(DeploymentOutcome.SUCCEEDED);
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getVersionLock()).isZero();
        assertThat(environmentVersionRepository.findByPipelineId(pipeline.getId())).hasSize(1);

        // All projection fields are nullable — the post-double-rollback "unknown" shape persists.
        reloaded.setCurrentVersion(null);
        reloaded.setCurrentRequestId(null);
        reloaded.setDeployedAt(null);
        reloaded.setPreviousVersion(null);
        reloaded.setPreviousRequestId(null);
        reloaded.setPreviousDeployedAt(null);
        reloaded.setLastOutcome(DeploymentOutcome.ROLLED_BACK);
        environmentVersionRepository.saveAndFlush(reloaded);
        assertThat(environmentVersionRepository.findByEnvironmentId(environment.getId())
                .orElseThrow().getCurrentVersion()).isNull();
    }

    @Test
    void environmentVersionRowIsUniquePerEnvironmentAndCascadesWithIt() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "production");

        var row = new DeploymentEnvironmentVersionEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(pipeline.getOrganizationId());
        row.setPipelineId(pipeline.getId());
        row.setEnvironmentId(environment.getId());
        environmentVersionRepository.saveAndFlush(row);

        var duplicate = new DeploymentEnvironmentVersionEntity();
        duplicate.setId(UUID.randomUUID());
        duplicate.setOrganizationId(pipeline.getOrganizationId());
        duplicate.setPipelineId(pipeline.getId());
        duplicate.setEnvironmentId(environment.getId());
        assertThatThrownBy(() -> environmentVersionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The FK is ON DELETE CASCADE: the projection row goes with its environment.
        jdbcTemplate.update("DELETE FROM deployment_environments WHERE id = ?",
                environment.getId());
        assertThat(environmentVersionRepository.findByEnvironmentId(environment.getId())).isEmpty();
    }

    @Test
    void persistsAndReloadsOneOffAndRecurringFreezeWindows() {
        var pipeline = newPipeline();

        var oneOff = new DeploymentFreezeWindowEntity();
        oneOff.setId(UUID.randomUUID());
        oneOff.setOrganizationId(pipeline.getOrganizationId());
        oneOff.setPipelineId(pipeline.getId());
        oneOff.setStartsAt(Instant.parse("2026-12-24T00:00:00Z"));
        oneOff.setEndsAt(Instant.parse("2027-01-02T00:00:00Z"));
        oneOff.setBehavior(FreezeBehavior.REJECT);
        oneOff.setReason("holiday freeze");
        freezeWindowRepository.saveAndFlush(oneOff);

        var recurring = new DeploymentFreezeWindowEntity();
        recurring.setId(UUID.randomUUID());
        recurring.setOrganizationId(pipeline.getOrganizationId());
        recurring.setDaysOfWeek(new short[]{5, 6, 7});
        recurring.setStartTime(LocalTime.of(18, 0));
        recurring.setEndTime(LocalTime.of(23, 59));
        recurring.setTimezone("Europe/Berlin");
        recurring.setBehavior(FreezeBehavior.HOLD);
        freezeWindowRepository.saveAndFlush(recurring);

        var reloadedOneOff = freezeWindowRepository.findById(oneOff.getId()).orElseThrow();
        assertThat(reloadedOneOff.getBehavior()).isEqualTo(FreezeBehavior.REJECT);
        assertThat(reloadedOneOff.getStartsAt()).isEqualTo(Instant.parse("2026-12-24T00:00:00Z"));
        assertThat(reloadedOneOff.getDaysOfWeek()).isNull();

        var reloadedRecurring = freezeWindowRepository.findById(recurring.getId()).orElseThrow();
        assertThat(reloadedRecurring.getBehavior()).isEqualTo(FreezeBehavior.HOLD);
        assertThat(reloadedRecurring.getDaysOfWeek()).containsExactly((short) 5, (short) 6, (short) 7);
        assertThat(reloadedRecurring.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(reloadedRecurring.getEndTime()).isEqualTo(LocalTime.of(23, 59));
        assertThat(reloadedRecurring.getTimezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void freezeWindowShapeCheckRejectsMixedShape() {
        var mixed = new DeploymentFreezeWindowEntity();
        mixed.setId(UUID.randomUUID());
        mixed.setOrganizationId(UUID.randomUUID());
        mixed.setStartsAt(Instant.now());
        mixed.setBehavior(FreezeBehavior.HOLD);

        assertThatThrownBy(() -> freezeWindowRepository.saveAndFlush(mixed))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsAndReloadsRequestWithEnumsJsonbAndOutcome() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "staging");

        var request = newRequest(pipeline.getId(), environment.getId(), "2.4.1", "run-1");
        request.setMetadata("{\"changelog\":\"fix rounding\"}");
        request.setStatus(QueryStatus.EXECUTED);
        request.setOutcome(DeploymentOutcome.ROLLED_BACK);
        request.setOutcomeReportedAt(Instant.parse("2026-08-03T09:00:00Z"));
        request.setOutcomeDetail("smoke tests failed");
        requestRepository.saveAndFlush(request);

        var reloaded = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(reloaded.getOutcome()).isEqualTo(DeploymentOutcome.ROLLED_BACK);
        assertThat(reloaded.getMetadata()).contains("fix rounding");
        assertThat(reloaded.getVersion()).isEqualTo("2.4.1");
        assertThat(reloaded.getVersionLock()).isZero();
    }

    @Test
    void gateLookupReturnsRequestsNewestFirst() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "production");

        var first = newRequest(pipeline.getId(), environment.getId(), "2.4.1", "run-a");
        first.setCreatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        requestRepository.saveAndFlush(first);
        var second = newRequest(pipeline.getId(), environment.getId(), "2.4.1", "run-b");
        second.setCreatedAt(Instant.parse("2026-08-02T10:00:00Z"));
        requestRepository.saveAndFlush(second);
        var otherVersion = newRequest(pipeline.getId(), environment.getId(), "2.4.2", "run-c");
        requestRepository.saveAndFlush(otherVersion);

        var found = requestRepository.findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
                pipeline.getId(), environment.getId(), "2.4.1");

        assertThat(found).extracting(DeploymentRequestEntity::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void triggerIdempotencyIndexRejectsDuplicateExternalRunButAllowsNulls() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "production");

        requestRepository.saveAndFlush(
                newRequest(pipeline.getId(), environment.getId(), "2.4.1", "run-42"));
        assertThatThrownBy(() -> requestRepository.saveAndFlush(
                newRequest(pipeline.getId(), environment.getId(), "2.4.1", "run-42")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Partial index: rows without an external run id are never deduplicated.
        requestRepository.saveAndFlush(
                newRequest(pipeline.getId(), environment.getId(), "2.4.1", null));
        requestRepository.saveAndFlush(
                newRequest(pipeline.getId(), environment.getId(), "2.4.1", null));
    }

    @Test
    void recordsDecisionsAndCountsApprovalsPerStage() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "production");
        var request = requestRepository.saveAndFlush(
                newRequest(pipeline.getId(), environment.getId(), "2.4.1", "run-7"));

        var approve = new DeploymentReviewDecisionEntity();
        approve.setId(UUID.randomUUID());
        approve.setDeploymentRequestId(request.getId());
        approve.setReviewerId(UUID.randomUUID());
        approve.setDecision(DecisionType.APPROVED);
        approve.setComment("lgtm");
        decisionRepository.saveAndFlush(approve);

        var reject = new DeploymentReviewDecisionEntity();
        reject.setId(UUID.randomUUID());
        reject.setDeploymentRequestId(request.getId());
        reject.setReviewerId(UUID.randomUUID());
        reject.setDecision(DecisionType.REJECTED);
        decisionRepository.saveAndFlush(reject);

        assertThat(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(
                request.getId(), 1, DecisionType.APPROVED)).isEqualTo(1);
        assertThat(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(
                request.getId(), 2, DecisionType.APPROVED)).isZero();
        assertThat(decisionRepository.findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(
                request.getId())).hasSize(2);
    }

    @Test
    void persistsAndReloadsRoutingPolicy() {
        var policy = new DeploymentRoutingPolicyEntity();
        policy.setId(UUID.randomUUID());
        policy.setOrganizationId(UUID.randomUUID());
        policy.setName("auto-approve dev");
        policy.setConditions("{\"environment\":\"dev\"}");
        policy.setAction(DeploymentRoutingAction.AUTO_APPROVE);
        routingPolicyRepository.saveAndFlush(policy);

        var reloaded = routingPolicyRepository.findById(policy.getId()).orElseThrow();
        assertThat(reloaded.getAction()).isEqualTo(DeploymentRoutingAction.AUTO_APPROVE);
        assertThat(reloaded.getConditions()).contains("dev");
        assertThat(reloaded.getPriority()).isEqualTo(100);
        assertThat(reloaded.isEnabled()).isTrue();
    }

    @Test
    void persistsAndReloadsUserAndGroupPermissions() {
        var pipeline = newPipeline();

        var userGrant = new DeploymentPipelineUserPermissionEntity();
        userGrant.setId(UUID.randomUUID());
        userGrant.setPipelineId(pipeline.getId());
        userGrant.setUserId(UUID.randomUUID());
        userGrant.setCanTrigger(true);
        userGrant.setExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));
        userGrant.setCreatedBy(UUID.randomUUID());
        userPermissionRepository.saveAndFlush(userGrant);

        var reloadedUserGrant = userPermissionRepository.findById(userGrant.getId()).orElseThrow();
        assertThat(reloadedUserGrant.isCanTrigger()).isTrue();
        assertThat(reloadedUserGrant.isCanBreakGlass()).isFalse();
        assertThat(reloadedUserGrant.getExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));

        // The group table keeps its V111-template FKs, so the org / group / creator must be real rows.
        var organizationId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var creatorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                organizationId, "deploygov-it-org", "deploygov-it-" + organizationId);
        jdbcTemplate.update(
                "INSERT INTO users (id, organization_id, email) VALUES (?, ?, ?)",
                creatorId, organizationId, creatorId + "@example.com");
        jdbcTemplate.update(
                "INSERT INTO user_groups (id, organization_id, name) VALUES (?, ?, ?)",
                groupId, organizationId, "deploygov-it-group");

        var groupGrant = new DeploymentPipelineGroupPermissionEntity();
        groupGrant.setId(UUID.randomUUID());
        groupGrant.setOrganizationId(organizationId);
        groupGrant.setPipelineId(pipeline.getId());
        groupGrant.setGroupId(groupId);
        groupGrant.setCanBreakGlass(true);
        groupGrant.setCreatedBy(creatorId);
        groupPermissionRepository.saveAndFlush(groupGrant);

        var reloadedGroupGrant = groupPermissionRepository.findById(groupGrant.getId()).orElseThrow();
        assertThat(reloadedGroupGrant.isCanTrigger()).isFalse();
        assertThat(reloadedGroupGrant.isCanBreakGlass()).isTrue();
        assertThat(reloadedGroupGrant.getVersion()).isZero();
    }

    @Test
    void persistsAndReloadsRollbackReviewAndEnforcesOnePerRequest() {
        var pipeline = newPipeline();
        var environment = newEnvironment(pipeline.getId(), "production");
        var request = newRequest(pipeline.getId(), environment.getId(), "2.4.1", "run-rb");
        request.setStatus(QueryStatus.EXECUTED);
        requestRepository.saveAndFlush(request);

        var review = new DeploymentRollbackReviewEntity();
        review.setId(UUID.randomUUID());
        review.setDeploymentRequestId(request.getId());
        review.setOrganizationId(request.getOrganizationId());
        review.setPipelineId(pipeline.getId());
        review.setEnvironmentId(environment.getId());
        review.setSubmittedBy(request.getSubmittedBy());
        review.setOutcomeDetail("smoke tests failed");
        rollbackReviewRepository.saveAndFlush(review);

        var reloaded = rollbackReviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeploymentRollbackReviewStatus.PENDING_REVIEW);
        assertThat(reloaded.getOutcomeDetail()).isEqualTo("smoke tests failed");
        assertThat(reloaded.getVersionLock()).isZero();
        assertThat(reloaded.getCreatedAt()).isNotNull();

        reloaded.setStatus(DeploymentRollbackReviewStatus.REVIEWED);
        reloaded.setReviewedBy(UUID.randomUUID());
        reloaded.setReviewedAt(Instant.parse("2026-08-03T09:00:00Z"));
        rollbackReviewRepository.saveAndFlush(reloaded);
        assertThat(rollbackReviewRepository.findById(review.getId()).orElseThrow().getStatus())
                .isEqualTo(DeploymentRollbackReviewStatus.REVIEWED);

        // UNIQUE deployment_request_id — the idempotency backstop for a repeated ROLLED_BACK report.
        var duplicate = new DeploymentRollbackReviewEntity();
        duplicate.setId(UUID.randomUUID());
        duplicate.setDeploymentRequestId(request.getId());
        duplicate.setOrganizationId(request.getOrganizationId());
        duplicate.setPipelineId(pipeline.getId());
        duplicate.setEnvironmentId(environment.getId());
        duplicate.setSubmittedBy(request.getSubmittedBy());
        assertThatThrownBy(() -> rollbackReviewRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
