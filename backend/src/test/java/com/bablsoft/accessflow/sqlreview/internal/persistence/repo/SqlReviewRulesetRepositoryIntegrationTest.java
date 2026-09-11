package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRuleConfigEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRulesetEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the V170 shape of the ruleset tables: the two partial unique indexes (one ruleset per
 * environment per organization, at most one organization default), the PG-enum and JSONB
 * mappings, and the ruleset → rule-config cascade.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SqlReviewRulesetRepositoryIntegrationTest {

    @Autowired SqlReviewRulesetRepository rulesetRepository;
    @Autowired SqlReviewRuleConfigRepository ruleConfigRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private OrganizationEntity organization;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(newOrg());
    }

    @AfterEach
    void cleanup() {
        // Only this class's rows: the organization FK cascades its rulesets and their configs.
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    void secondRulesetForTheSameEnvironmentIsRejected() {
        rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.PRODUCTION));

        assertThatThrownBy(() -> rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.PRODUCTION)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondOrganizationDefaultIsRejected() {
        rulesetRepository.saveAndFlush(newRuleset(null));

        assertThatThrownBy(() -> rulesetRepository.saveAndFlush(newRuleset(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentEnvironmentsPlusTheDefaultCoexistInOneOrganization() {
        var staging = rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.STAGING));
        var production = rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.PRODUCTION));
        var fallback = rulesetRepository.saveAndFlush(newRuleset(null));

        assertThat(rulesetRepository.findAllByOrganizationIdOrderByNameAsc(organization.getId()))
                .extracting(SqlReviewRulesetEntity::getId)
                .containsExactlyInAnyOrder(staging.getId(), production.getId(), fallback.getId());
        assertThat(rulesetRepository.findByOrganizationIdAndEnvironment(
                organization.getId(), DatasourceEnvironment.STAGING))
                .map(SqlReviewRulesetEntity::getId).contains(staging.getId());
        assertThat(rulesetRepository.findByOrganizationIdAndEnvironment(
                organization.getId(), DatasourceEnvironment.TEST)).isEmpty();
        assertThat(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(organization.getId()))
                .map(SqlReviewRulesetEntity::getId).contains(fallback.getId());
        assertThat(rulesetRepository.findByIdAndOrganizationId(staging.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void sameEnvironmentInAnotherOrganizationIsAccepted() {
        var other = organizationRepository.save(newOrg());
        try {
            rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.PRODUCTION));
            var foreign = newRuleset(DatasourceEnvironment.PRODUCTION);
            foreign.setOrganizationId(other.getId());

            assertThat(rulesetRepository.saveAndFlush(foreign).getId()).isNotNull();
        } finally {
            organizationRepository.deleteById(other.getId());
        }
    }

    @Test
    void ruleConfigRoundTripsEnumAndJsonAndIsUniquePerRule() {
        var ruleset = rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.DEVELOPMENT));
        var config = newConfig(ruleset, "disallowed_function", "{\"names\": [\"pg_sleep\"]}");
        ruleConfigRepository.saveAndFlush(config);

        var reloaded = ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(ruleset.getId());
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.getFirst().getSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(reloaded.getFirst().getParams()).contains("pg_sleep");
        assertThat(reloaded.getFirst().getRuleset().getId()).isEqualTo(ruleset.getId());

        assertThatThrownBy(() -> ruleConfigRepository.saveAndFlush(
                newConfig(ruleset, "disallowed_function", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingARulesetCascadesItsRuleConfigs() {
        var ruleset = rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.TEST));
        ruleConfigRepository.saveAndFlush(newConfig(ruleset, "select_star", null));
        ruleConfigRepository.saveAndFlush(newConfig(ruleset, "cross_join", null));

        jdbcTemplate.update("DELETE FROM sql_review_rulesets WHERE id = ?", ruleset.getId());

        assertThat(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(ruleset.getId())).isEmpty();
        assertThat(rulesetRepository.findById(ruleset.getId())).isEmpty();
    }

    @Test
    void deleteAllByRulesetIdRemovesOnlyThatRulesetsConfigs() {
        var ruleset = rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.STAGING));
        var other = rulesetRepository.saveAndFlush(newRuleset(DatasourceEnvironment.PRODUCTION));
        ruleConfigRepository.saveAndFlush(newConfig(ruleset, "select_star", null));
        ruleConfigRepository.saveAndFlush(newConfig(other, "select_star", null));

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ruleConfigRepository.deleteAllByRulesetId(ruleset.getId()));

        assertThat(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(ruleset.getId())).isEmpty();
        assertThat(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(other.getId())).hasSize(1);
    }

    private SqlReviewRulesetEntity newRuleset(DatasourceEnvironment environment) {
        var entity = new SqlReviewRulesetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setName("ruleset-" + UUID.randomUUID());
        entity.setEnvironment(environment);
        return entity;
    }

    private SqlReviewRuleConfigEntity newConfig(SqlReviewRulesetEntity ruleset, String ruleId, String params) {
        var entity = new SqlReviewRuleConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setRuleset(ruleset);
        entity.setRuleId(ruleId);
        entity.setSeverity(SqlReviewSeverity.BLOCK);
        entity.setParams(params);
        return entity;
    }

    private OrganizationEntity newOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("SqlReview-" + UUID.randomUUID());
        org.setSlug("sqlreview-" + UUID.randomUUID());
        return org;
    }
}
