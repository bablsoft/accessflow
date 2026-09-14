package com.bablsoft.accessflow.requestgroups;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.requestgroups.api.CreateRequestGroupCommand;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #864 on the request-group path (AF-501): members are evaluated when the group is submitted, the
 * findings are persisted per member and surfaced on the group detail, and a member's {@code BLOCK}
 * turns the group's plan-only fast path into human review — audited once, and only when it changed
 * the outcome. The AI preview is unconfigured here and fails safe, exactly as it would in production
 * without a provider.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class GroupSqlReviewEnforcementIntegrationTest {

    @Autowired RequestGroupService requestGroupService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private DatasourceEntity datasource;

    @BeforeEach
    void setUp() {
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        submitter = new UserEntity();
        submitter.setId(UUID.randomUUID());
        submitter.setEmail("admin-" + UUID.randomUUID() + "@example.com");
        submitter.setDisplayName("Admin");
        submitter.setPasswordHash("hash");
        submitter.setRole(UserRoleType.ADMIN);
        submitter.setAuthProvider(AuthProviderType.LOCAL);
        submitter.setActive(true);
        submitter.setOrganization(organization);
        userRepository.save(submitter);

        // A plan that would approve without a human: the only path a member BLOCK can suppress.
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(organization);
        plan.setName("plan-" + UUID.randomUUID());
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(false);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        reviewPlanRepository.save(plan);
        var rule = new ReviewPlanApproverEntity();
        rule.setId(UUID.randomUUID());
        rule.setReviewPlan(plan);
        rule.setRole("REVIEWER");
        rule.setStage(1);
        reviewPlanApproverRepository.save(rule);

        datasource = new DatasourceEntity();
        datasource.setId(UUID.randomUUID());
        datasource.setOrganization(organization);
        datasource.setName("DS-" + UUID.randomUUID());
        datasource.setDbType(DbType.POSTGRESQL);
        datasource.setHost("nope.invalid");
        datasource.setPort(65000);
        datasource.setDatabaseName("db");
        datasource.setUsername("u");
        datasource.setPasswordEncrypted(encryptionService.encrypt("p"));
        datasource.setSslMode(SslMode.DISABLE);
        datasource.setConnectionPoolSize(5);
        datasource.setMaxRowsPerQuery(1000);
        datasource.setRequireReviewReads(false);
        datasource.setRequireReviewWrites(true);
        datasource.setReviewPlan(plan);
        datasource.setAiAnalysisEnabled(false);
        datasource.setActive(true);
        datasourceRepository.save(datasource);
    }

    @AfterEach
    void cleanup() {
        // Groups carry no FK to organizations; scope by the ids this class created.
        jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organization.getId());
        jdbcTemplate.update("DELETE FROM sql_review_rulesets WHERE organization_id = ?",
                organization.getId());
        jdbcTemplate.update("DELETE FROM request_groups WHERE organization_id = ?",
                organization.getId());
        datasourceRepository.deleteById(datasource.getId());
        var plan = reviewPlanRepository.findAll().stream()
                .filter(p -> p.getOrganization().getId().equals(organization.getId())).toList();
        plan.forEach(p -> {
            reviewPlanApproverRepository.findAll().stream()
                    .filter(a -> a.getReviewPlan().getId().equals(p.getId()))
                    .forEach(reviewPlanApproverRepository::delete);
            reviewPlanRepository.delete(p);
        });
        userRepository.deleteById(submitter.getId());
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    void aMemberBlockForcesTheGroupToReviewAndSurfacesTheFindings() {
        seedRuleset(SqlReviewSeverity.BLOCK);
        var groupId = draftWithOneQueryMember("SELECT * FROM orders");

        requestGroupService.submit(new SubmitRequestGroupCommand(groupId, organization.getId(),
                submitter.getId(), true, false, null, null, null));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                requestGroupService.get(groupId, organization.getId(), submitter.getId(), true)
                        .status()).isEqualTo(RequestGroupStatus.PENDING_REVIEW));
        var view = requestGroupService.get(groupId, organization.getId(), submitter.getId(), true);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).sqlReviewFindings())
                .anyMatch(f -> f.ruleId().equals("select_star") && f.isBlocking());
        assertThat(jdbcTemplate.queryForList("SELECT metadata::text FROM audit_log "
                + "WHERE resource_id = ? AND action = 'SQL_REVIEW_BLOCKED'", String.class, groupId))
                .singleElement().asString()
                .contains("\"trigger\": \"sql_review\"")
                .contains("GROUP_REVIEW_PLAN")
                .contains("select_star");
    }

    @Test
    void warnFindingsLeaveTheGroupFastPathAlone() {
        seedRuleset(SqlReviewSeverity.WARN);
        var groupId = draftWithOneQueryMember("SELECT * FROM orders");

        requestGroupService.submit(new SubmitRequestGroupCommand(groupId, organization.getId(),
                submitter.getId(), true, false, null, null, null));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                requestGroupService.get(groupId, organization.getId(), submitter.getId(), true)
                        .status()).isEqualTo(RequestGroupStatus.APPROVED));
        var view = requestGroupService.get(groupId, organization.getId(), submitter.getId(), true);
        assertThat(view.items().get(0).sqlReviewFindings())
                .anyMatch(f -> f.ruleId().equals("select_star")
                        && f.severity() == SqlReviewSeverity.WARN);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log "
                + "WHERE resource_id = ? AND action = 'SQL_REVIEW_BLOCKED'", Long.class, groupId))
                .isZero();
    }

    private UUID draftWithOneQueryMember(String sql) {
        var member = new RequestGroupItemInput(RequestGroupTargetKind.QUERY, 0, datasource.getId(),
                sql, false, null, null, null, null, null, null, null, null, null, null, null);
        return requestGroupService.createDraft(new CreateRequestGroupCommand(organization.getId(),
                submitter.getId(), true, "bundle-" + UUID.randomUUID(), null, false,
                List.of(member))).id();
    }

    private void seedRuleset(SqlReviewSeverity severity) {
        var rulesetId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO sql_review_rulesets (id, organization_id, name, environment, "
                + "enabled) VALUES (?, ?, ?, NULL, true)", rulesetId, organization.getId(),
                "Default " + rulesetId);
        jdbcTemplate.update("INSERT INTO sql_review_rule_configs (id, ruleset_id, rule_id, severity, "
                + "params) VALUES (?, ?, 'select_star', ?::sql_review_severity, NULL)",
                UUID.randomUUID(), rulesetId, severity.name());
    }
}
