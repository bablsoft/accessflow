package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionInventoryService;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentVersionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Runs the #742 inventory end-to-end against real PostgreSQL: the drift math over persisted
 * tracker rows (including the grouped {@code executed_at} projection the V157 migration backs),
 * the org-wide filters through real SQL, the history finders, and — over MockMvc with API-key
 * auth — the permission matrix: no grant reads as 404 (never 403) on the per-pipeline endpoints
 * while the org-wide matrix answers 403 to a trigger-only caller.
 *
 * <p>No {@code deleteAll} cleanup: every identifier is randomized, so rows left in the shared
 * container never collide with other classes (and a blanket wipe would trip on their leftovers).
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentVersionInventoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired WebApplicationContext context;
    @Autowired DeploymentVersionInventoryService inventoryService;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentEnvironmentVersionRepository versionRepository;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired DeploymentPipelineUserPermissionRepository userPermissionRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApiKeyService apiKeyService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity reviewer;
    private DeploymentPipelineEntity pipeline;
    private DeploymentEnvironmentEntity staging;
    private DeploymentEnvironmentEntity prod;

    @BeforeEach
    void seed() {
        mvc = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        org = organization();
        reviewer = user(org, UserRoleType.REVIEWER);
        pipeline = pipeline(org, "payments-api");
        staging = environment(pipeline, "staging", 0, new String[0]);
        prod = environment(pipeline, "prod-acme", 1, new String[] {"prod", "acme"});
        // staging runs the latest 2.4.1; prod-acme sits four days behind on 2.4.0.
        versionRow(staging, "2.4.1", NOW, null);
        versionRow(prod, "2.4.0", NOW.minusSeconds(4 * 86_400), DeploymentOutcome.SUCCEEDED);
        executedRequest(staging, "2.4.0", NOW.minusSeconds(4 * 86_400), DeploymentOutcome.SUCCEEDED);
        executedRequest(staging, "2.4.1", NOW, null);
    }

    @Test
    void pipelineMatrixComputesDriftOverPersistedRowsAndRealExecutions() {
        var matrix = inventoryService.pipelineMatrix(pipeline.getId(), org.getId(),
                reviewer.getId(), Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(matrix).hasSize(2);
        var stagingView = matrix.getFirst();
        assertThat(stagingView.environmentName()).isEqualTo(staging.getName());
        assertThat(stagingView.drift().drifted()).isFalse();
        assertThat(stagingView.drift().daysBehind()).isZero();
        var prodView = matrix.get(1);
        assertThat(prodView.environmentName()).isEqualTo(prod.getName());
        assertThat(prodView.tags()).containsExactly("prod", "acme");
        assertThat(prodView.currentVersion()).isEqualTo("2.4.0");
        assertThat(prodView.drift().latestVersion()).isEqualTo("2.4.1");
        assertThat(prodView.drift().drifted()).isTrue();
        assertThat(prodView.drift().daysBehind()).isEqualTo(4L);
        // The grouped executed_at projection: only 2.4.1 executed after prod's deploy.
        assertThat(prodView.drift().deploymentsBehind()).isEqualTo(1L);
    }

    @Test
    void listFiltersByTagAndDriftedThroughRealSql() {
        var tagged = inventoryService.list(new DeploymentEnvironmentVersionListFilter(
                org.getId(), null, "acme", null, null), PageRequest.of(0, 20));
        assertThat(tagged.content()).hasSize(1);
        assertThat(tagged.content().getFirst().environmentName()).isEqualTo(prod.getName());
        // The tag filter narrowed the rows, not the comparison target.
        assertThat(tagged.content().getFirst().drift().latestVersion()).isEqualTo("2.4.1");

        var drifted = inventoryService.list(new DeploymentEnvironmentVersionListFilter(
                org.getId(), pipeline.getId(), null, null, true), PageRequest.of(0, 20));
        assertThat(drifted.content()).hasSize(1);
        assertThat(drifted.content().getFirst().environmentName()).isEqualTo(prod.getName());
    }

    @Test
    void historyListsTheEnvironmentsRequestsNewestFirstWithAStatusFilter() {
        var all = inventoryService.history(pipeline.getId(), staging.getId(), null, org.getId(),
                reviewer.getId(), Set.of(Permission.DEPLOYMENT_REVIEW), PageRequest.of(0, 20));
        assertThat(all.totalElements()).isEqualTo(2);
        assertThat(all.content().getFirst().version()).isEqualTo("2.4.1");
        assertThat(all.content().getFirst().executedAt()).isEqualTo(NOW);

        var executed = inventoryService.history(pipeline.getId(), staging.getId(),
                QueryStatus.EXECUTED, org.getId(), reviewer.getId(),
                Set.of(Permission.DEPLOYMENT_REVIEW), PageRequest.of(0, 20));
        assertThat(executed.totalElements()).isEqualTo(2);
    }

    @Test
    void permissionMatrixOverTheWebLayer() {
        var analyst = user(org, UserRoleType.ANALYST);
        var analystKey = apiKeyService.issue(analyst.getId(), org.getId(),
                "ci-" + UUID.randomUUID(), null).rawKey();
        var reviewerKey = apiKeyService.issue(reviewer.getId(), org.getId(),
                "rev-" + UUID.randomUUID(), null).rawKey();

        // No grant: the per-pipeline endpoints read as 404 — never 403.
        var noGrant = mvc.get()
                .uri("/api/v1/deployment-pipelines/" + pipeline.getId() + "/environment-versions")
                .header(API_KEY_HEADER, analystKey).exchange();
        assertThat(noGrant).hasStatus(404);
        assertThat(noGrant).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DEPLOYMENT_PIPELINE_NOT_FOUND");

        // An effective can_trigger grant opens the per-pipeline endpoints…
        var grant = new DeploymentPipelineUserPermissionEntity();
        grant.setId(UUID.randomUUID());
        grant.setPipelineId(pipeline.getId());
        grant.setUserId(analyst.getId());
        grant.setCanTrigger(true);
        grant.setCreatedBy(reviewer.getId());
        userPermissionRepository.saveAndFlush(grant);

        var granted = mvc.get()
                .uri("/api/v1/deployment-pipelines/" + pipeline.getId() + "/environment-versions")
                .header(API_KEY_HEADER, analystKey).exchange();
        assertThat(granted).hasStatus(200);

        var history = mvc.get()
                .uri("/api/v1/deployment-pipelines/" + pipeline.getId() + "/environments/"
                        + staging.getId() + "/history")
                .header(API_KEY_HEADER, analystKey).exchange();
        assertThat(history).hasStatus(200);

        // …but the org-wide matrix stays functional-permission-only: trigger-only → 403.
        var orgWideDenied = mvc.get().uri("/api/v1/deployment-environment-versions")
                .header(API_KEY_HEADER, analystKey).exchange();
        assertThat(orgWideDenied).hasStatus(403);

        var orgWide = mvc.get().uri("/api/v1/deployment-environment-versions?drifted=true")
                .header(API_KEY_HEADER, reviewerKey).exchange();
        assertThat(orgWide).hasStatus(200);
        assertThat(orgWide).bodyJson().extractingPath("$.content[0].environment.name").asString()
                .isEqualTo(prod.getName());
        assertThat(orgWide).bodyJson().extractingPath("$.content[0].drift.drifted")
                .isEqualTo(true);
    }

    @Test
    void crossOrgPipelineReadsAsNotFoundEvenForAReviewer() {
        var otherOrg = organization();
        var otherReviewer = user(otherOrg, UserRoleType.REVIEWER);
        var otherKey = apiKeyService.issue(otherReviewer.getId(), otherOrg.getId(),
                "rev-" + UUID.randomUUID(), null).rawKey();

        var result = mvc.get()
                .uri("/api/v1/deployment-pipelines/" + pipeline.getId() + "/environment-versions")
                .header(API_KEY_HEADER, otherKey).exchange();
        assertThat(result).hasStatus(404);
    }

    // ---------------------------------------------------------------- fixtures

    private OrganizationEntity organization() {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("Acme-" + UUID.randomUUID());
        entity.setSlug("acme-" + UUID.randomUUID());
        return organizationRepository.save(entity);
    }

    private UserEntity user(OrganizationEntity organization, UserRoleType role) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(UUID.randomUUID() + "@example.com");
        entity.setDisplayName("User");
        entity.setPasswordHash("x");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(organization);
        return userRepository.save(entity);
    }

    private DeploymentPipelineEntity pipeline(OrganizationEntity organization, String name) {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setName(name + "-" + UUID.randomUUID());
        entity.setProvider(PipelineProvider.GENERIC);
        return pipelineRepository.save(entity);
    }

    private DeploymentEnvironmentEntity environment(DeploymentPipelineEntity owner, String name,
                                                    int sortOrder, String[] tags) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(owner.getId());
        entity.setName(name);
        entity.setSortOrder(sortOrder);
        entity.setTags(tags);
        return environmentRepository.save(entity);
    }

    private void versionRow(DeploymentEnvironmentEntity environment, String currentVersion,
                            Instant deployedAt, DeploymentOutcome lastOutcome) {
        var row = new DeploymentEnvironmentVersionEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(org.getId());
        row.setPipelineId(pipeline.getId());
        row.setEnvironmentId(environment.getId());
        row.setCurrentVersion(currentVersion);
        row.setCurrentRequestId(UUID.randomUUID());
        row.setDeployedAt(deployedAt);
        row.setLastOutcome(lastOutcome);
        versionRepository.saveAndFlush(row);
    }

    private void executedRequest(DeploymentEnvironmentEntity environment, String version,
                                 Instant executedAt, DeploymentOutcome outcome) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setPipelineId(pipeline.getId());
        entity.setEnvironmentId(environment.getId());
        entity.setSubmittedBy(reviewer.getId());
        entity.setVersion(version);
        entity.setStatus(QueryStatus.EXECUTED);
        entity.setOutcome(outcome);
        entity.setExecutedAt(executedAt);
        requestRepository.saveAndFlush(entity);
    }
}
