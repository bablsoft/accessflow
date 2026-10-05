package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewCustomRuleEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the V201 shape of {@code sql_review_custom_rules}: the two PG enum mappings, the JSONB
 * condition, the {@code custom_} rule-id CHECK, per-organization uniqueness, and the enabled-only,
 * rule-id-ordered listing the rule source reads.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SqlReviewCustomRuleRepositoryIntegrationTest {

    private static final String CONDITION = "{\"type\": \"function_called\", \"names\": [\"dblink\"]}";

    @Autowired SqlReviewCustomRuleRepository repository;
    @Autowired OrganizationRepository organizationRepository;

    private OrganizationEntity organization;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(newOrg());
    }

    @AfterEach
    void cleanup() {
        // The organization FK cascades this class's custom rules.
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    void persistsEnumsAndJsonAndListsEnabledRulesInRuleIdOrder() {
        repository.saveAndFlush(newRule(organization.getId(), "custom_zeta", true));
        repository.saveAndFlush(newRule(organization.getId(), "custom_alpha", true));
        repository.saveAndFlush(newRule(organization.getId(), "custom_disabled", false));

        var rules = repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(organization.getId());

        assertThat(rules).extracting(SqlReviewCustomRuleEntity::getRuleId)
                .containsExactly("custom_alpha", "custom_zeta");
        assertThat(rules.get(0).getCategory()).isEqualTo(SqlRuleCategory.PERFORMANCE);
        assertThat(rules.get(0).getDefaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rules.get(0).getCondition()).contains("dblink");
    }

    @Test
    void ruleIdsAreUniquePerOrganizationOnly() {
        repository.saveAndFlush(newRule(organization.getId(), "custom_dup", true));
        var other = organizationRepository.save(newOrg());
        try {
            repository.saveAndFlush(newRule(other.getId(), "custom_dup", true));
            assertThatThrownBy(() -> repository.saveAndFlush(newRule(organization.getId(), "custom_dup", true)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(repository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(other.getId())).hasSize(1);
        } finally {
            organizationRepository.deleteById(other.getId());
        }
    }

    @Test
    void ruleIdMustCarryTheCustomPrefixAndSlugShape() {
        for (String bad : new String[] {"select_star", "custom_", "custom_ab", "custom_Upper", "custom_1abc"}) {
            assertThatThrownBy(() -> repository.saveAndFlush(newRule(organization.getId(), bad, true)))
                    .as(bad).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private static SqlReviewCustomRuleEntity newRule(UUID organizationId, String ruleId, boolean enabled) {
        var entity = new SqlReviewCustomRuleEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setRuleId(ruleId);
        entity.setName("Rule " + ruleId);
        entity.setMessage("matched");
        entity.setCategory(SqlRuleCategory.PERFORMANCE);
        entity.setDefaultSeverity(SqlReviewSeverity.WARN);
        entity.setCondition(CONDITION);
        entity.setEnabled(enabled);
        return entity;
    }

    private static OrganizationEntity newOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("SqlReviewCustom-" + UUID.randomUUID());
        org.setSlug("sqlreview-custom-" + UUID.randomUUID());
        return org;
    }
}
