package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentVersionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the tag/pipeline listing Specification against real PostgreSQL — the correlated
 * {@code EXISTS} subquery with {@code array_position} over {@code deployment_environments.tags}
 * is exactly the kind of construct a mocked CriteriaBuilder cannot prove (#741).
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentEnvironmentVersionSpecificationsIntegrationTest {

    @Autowired
    private DeploymentPipelineRepository pipelineRepository;
    @Autowired
    private DeploymentEnvironmentRepository environmentRepository;
    @Autowired
    private DeploymentEnvironmentVersionRepository versionRepository;

    private UUID organizationId;
    private UUID pipelineId;
    private UUID taggedRowId;
    private UUID untaggedRowId;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(organizationId);
        pipeline.setName("payments-api-" + UUID.randomUUID());
        pipeline.setProvider(PipelineProvider.GENERIC);
        pipelineRepository.saveAndFlush(pipeline);
        pipelineId = pipeline.getId();

        taggedRowId = versionRow(environment("prod-acme", new String[]{"acme", "eu"}));
        untaggedRowId = versionRow(environment("staging", new String[0]));
    }

    private DeploymentEnvironmentEntity environment(String name, String[] tags) {
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(UUID.randomUUID());
        environment.setPipelineId(pipelineId);
        environment.setName(name);
        environment.setTags(tags);
        return environmentRepository.saveAndFlush(environment);
    }

    private UUID versionRow(DeploymentEnvironmentEntity environment) {
        var row = new DeploymentEnvironmentVersionEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(organizationId);
        row.setPipelineId(pipelineId);
        row.setEnvironmentId(environment.getId());
        row.setCurrentVersion("1.0.0");
        return versionRepository.saveAndFlush(row).getId();
    }

    @Test
    void noFiltersListsEveryRowOfTheOrganization() {
        var rows = versionRepository.findAll(
                DeploymentEnvironmentVersionSpecifications.forList(organizationId, null, null));

        assertThat(rows).extracting(DeploymentEnvironmentVersionEntity::getId)
                .containsExactlyInAnyOrder(taggedRowId, untaggedRowId);
    }

    @Test
    void pipelineFilterScopesToThePipeline() {
        assertThat(versionRepository.findAll(
                DeploymentEnvironmentVersionSpecifications.forList(organizationId, pipelineId,
                        null))).hasSize(2);
        assertThat(versionRepository.findAll(
                DeploymentEnvironmentVersionSpecifications.forList(organizationId,
                        UUID.randomUUID(), null))).isEmpty();
    }

    @Test
    void tagFilterMatchesOnlyEnvironmentsCarryingTheTag() {
        var rows = versionRepository.findAll(
                DeploymentEnvironmentVersionSpecifications.forList(organizationId, null, "acme"));

        assertThat(rows).extracting(DeploymentEnvironmentVersionEntity::getId)
                .containsExactly(taggedRowId);
    }

    @Test
    void tagFilterTrimsAndMissesUnknownTags() {
        assertThat(versionRepository.findAll(
                DeploymentEnvironmentVersionSpecifications.forList(organizationId, null, " eu ")))
                .extracting(DeploymentEnvironmentVersionEntity::getId)
                .containsExactly(taggedRowId);
        assertThat(versionRepository.findAll(
                DeploymentEnvironmentVersionSpecifications.forList(organizationId, null,
                        "globex"))).isEmpty();
    }

    @Test
    void otherOrganizationsSeeNothing() {
        assertThat(versionRepository.findAll(
                DeploymentEnvironmentVersionSpecifications.forList(UUID.randomUUID(), null, null)))
                .isEmpty();
    }
}
