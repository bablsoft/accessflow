package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRuleConfigRepository;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRulesetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminSqlReviewRulesetControllerIntegrationTest extends SqlReviewIntegrationTestSupport {

    private static final String BASE = "/api/v1/admin/sql-review-rulesets";

    @Autowired SqlReviewRulesetRepository rulesetRepository;
    @Autowired SqlReviewRuleConfigRepository ruleConfigRepository;

    private String adminToken;
    private String stewardToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        seedOrganization();
        var admin = saveUser("admin", UserRoleType.ADMIN, null);
        var steward = saveUser("steward", null, saveCustomRole("Steward", Permission.QUERY_ADMIN));
        var analyst = saveUser("analyst", UserRoleType.ANALYST, null);
        adminToken = generateToken(admin);
        stewardToken = generateToken(steward);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        cleanupOrganization();
    }

    private MvcTestResult post(String token, String body) {
        return mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult put(String token, UUID id, String body) {
        return mvc.put().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private UUID create(String body) {
        var result = post(adminToken, body);
        assertThat(result).hasStatus(201);
        return idOf(result);
    }

    private static UUID idOf(MvcTestResult result) {
        try {
            return UUID.fromString(result.getResponse().getContentAsString()
                    .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final String PRODUCTION = """
            {"name":"Production","description":"Payroll is off limits","environment":"PRODUCTION",
             "rules":[{"rule_id":"select_star","severity":"OFF"},
                      {"rule_id":"protected_table","severity":"BLOCK","params":{"globs":["payroll.*"]}}]}
            """;

    @Test
    void createReadUpdateDeleteRoundTripWithAuditRows() {
        var created = post(adminToken, PRODUCTION);
        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.environment").asString().isEqualTo("PRODUCTION");
        assertThat(created).bodyJson().extractingPath("$.enabled").asBoolean().isTrue();
        assertThat(created).bodyJson().extractingPath("$.organization_id").asString().isEqualTo(org.getId().toString());
        assertThat(created).bodyJson().extractingPath("$.rules[0].rule_id").asString().isEqualTo("protected_table");
        assertThat(created).bodyJson().extractingPath("$.rules[0].params.globs[0]").asString().isEqualTo("payroll.*");
        assertThat(created).bodyJson().extractingPath("$.rules[1].rule_id").asString().isEqualTo("select_star");
        assertThat(created).bodyJson().extractingPath("$.rules[1].severity").asString().isEqualTo("OFF");
        var id = idOf(created);
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(BASE + "/" + id);
        assertThat(auditRows(AuditAction.SQL_REVIEW_RULESET_CREATED.name())).isEqualTo(1);

        var list = mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(list).hasStatus(200);
        assertThat(list).bodyJson().extractingPath("$.length()").asNumber().isEqualTo(1);
        assertThat(list).bodyJson().extractingPath("$[0].id").asString().isEqualTo(id.toString());

        var get = mvc.get().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(get).hasStatus(200);
        assertThat(get).bodyJson().extractingPath("$.description").asString().isEqualTo("Payroll is off limits");

        // PUT is total: no description, no environment and no rules clears all three.
        var updated = put(adminToken, id, "{\"name\":\"Org default\",\"enabled\":false}");
        assertThat(updated).hasStatus(200);
        assertThat(updated).bodyJson().extractingPath("$.name").asString().isEqualTo("Org default");
        assertThat(updated).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
        assertThat(updated).bodyJson().doesNotHavePath("$.environment");
        assertThat(updated).bodyJson().doesNotHavePath("$.description");
        assertThat(updated).bodyJson().extractingPath("$.rules.length()").asNumber().isEqualTo(0);
        assertThat(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(id)).isEmpty();
        assertThat(rulesetRepository.findById(id).orElseThrow().getEnvironment()).isNull();
        assertThat(auditRows(AuditAction.SQL_REVIEW_RULESET_UPDATED.name())).isEqualTo(1);

        var deleted = mvc.delete().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(deleted).hasStatus(204);
        assertThat(rulesetRepository.findById(id)).isEmpty();
        assertThat(auditRows(AuditAction.SQL_REVIEW_RULESET_DELETED.name())).isEqualTo(1);

        var gone = mvc.get().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(gone).hasStatus(404);
        assertThat(gone).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULESET_NOT_FOUND");
        assertThat(gone).bodyJson().extractingPath("$.rulesetId").asString().isEqualTo(id.toString());
    }

    @Test
    void callersWithoutSqlReviewManageAreForbidden() {
        for (var token : new String[]{analystToken, stewardToken}) {
            var list = mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
            assertThat(list).hasStatus(403);
            assertThat(post(token, PRODUCTION)).hasStatus(403);
        }
        assertThat(rulesetRepository.findAllByOrganizationIdOrderByNameAsc(org.getId())).isEmpty();
        assertThat(auditRows()).isZero();
    }

    @Test
    void aSecondRulesetOnTheSameEnvironmentIs409() {
        create(PRODUCTION);

        var duplicate = post(adminToken, "{\"name\":\"Prod again\",\"environment\":\"PRODUCTION\"}");

        assertThat(duplicate).hasStatus(409);
        assertThat(duplicate).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SQL_REVIEW_RULESET_ENVIRONMENT_CONFLICT");
        assertThat(duplicate).bodyJson().extractingPath("$.environment").asString().isEqualTo("PRODUCTION");
        assertThat(duplicate).bodyJson().extractingPath("$.detail").asString().contains("PRODUCTION");
        assertThat(auditRows(AuditAction.SQL_REVIEW_RULESET_CREATED.name())).isEqualTo(1);
    }

    @Test
    void aSecondOrganizationDefaultIs409OnCreateAndOnUpdate() {
        create("{\"name\":\"Default\"}");
        var staging = create("{\"name\":\"Staging\",\"environment\":\"STAGING\"}");

        var duplicate = post(adminToken, "{\"name\":\"Default again\"}");
        assertThat(duplicate).hasStatus(409);
        assertThat(duplicate).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SQL_REVIEW_RULESET_DEFAULT_CONFLICT");
        assertThat(duplicate).bodyJson().doesNotHavePath("$.environment");

        // Moving staging into the default slot collides with the existing default ...
        var moved = put(adminToken, staging, "{\"name\":\"Staging\"}");
        assertThat(moved).hasStatus(409);
        assertThat(moved).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULESET_DEFAULT_CONFLICT");
        assertThat(rulesetRepository.findById(staging).orElseThrow().getEnvironment().name()).isEqualTo("STAGING");
        // ... while re-sending its own environment is not a conflict with itself.
        assertThat(put(adminToken, staging, "{\"name\":\"Staging\",\"environment\":\"STAGING\"}")).hasStatus(200);
    }

    @Test
    void malformedRuleConfigsAre422AndUnreadableBodiesAre400() {
        var unknown = post(adminToken, "{\"name\":\"x\",\"rules\":[{\"rule_id\":\"no_such_rule\",\"severity\":\"WARN\"}]}");
        assertThat(unknown).hasStatus(422);
        assertThat(unknown).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULESET_INVALID");
        assertThat(unknown).bodyJson().extractingPath("$.detail").asString().contains("no_such_rule");

        var badGlob = post(adminToken, "{\"name\":\"x\",\"rules\":[{\"rule_id\":\"protected_table\","
                + "\"severity\":\"BLOCK\",\"params\":{\"globs\":[\"payroll.*; drop\"]}}]}");
        assertThat(badGlob).hasStatus(422);
        assertThat(badGlob).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULESET_INVALID");

        var duplicate = post(adminToken, "{\"name\":\"x\",\"rules\":[{\"rule_id\":\"select_star\",\"severity\":\"WARN\"},"
                + "{\"rule_id\":\"select_star\",\"severity\":\"OFF\"}]}");
        assertThat(duplicate).hasStatus(422);
        assertThat(duplicate).bodyJson().extractingPath("$.detail").asString().contains("select_star");

        var unreadable = post(adminToken, "{\"name\":\"x\",\"rules\":[{\"rule_id\":\"select_star\",\"severity\":\"LOUD\"}]}");
        assertThat(unreadable).hasStatus(400);
        assertThat(unreadable).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");

        var blank = post(adminToken, "{\"name\":\"  \"}");
        assertThat(blank).hasStatus(400);
        assertThat(blank).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");

        assertThat(rulesetRepository.findAllByOrganizationIdOrderByNameAsc(org.getId())).isEmpty();
        assertThat(auditRows()).isZero();
    }

    @Test
    void aRulesetInAnotherOrganizationIsNotFound() {
        var foreignId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into organizations (id, name, slug) values (?, ?, ?)
                """, foreignId, "Foreign " + suffix, "foreign-" + suffix);
        var foreignRuleset = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    insert into sql_review_rulesets (id, organization_id, name, environment, enabled)
                    values (?, ?, ?, 'PRODUCTION'::datasource_environment, true)
                    """, foreignRuleset, foreignId, "Theirs");

            var get = mvc.get().uri(BASE + "/" + foreignRuleset)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
            assertThat(get).hasStatus(404);
            assertThat(put(adminToken, foreignRuleset, "{\"name\":\"Mine now\"}")).hasStatus(404);
            var delete = mvc.delete().uri(BASE + "/" + foreignRuleset)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
            assertThat(delete).hasStatus(404);
            assertThat(rulesetRepository.findById(foreignRuleset)).isPresent();
            assertThat(auditRows()).isZero();
        } finally {
            jdbcTemplate.update("delete from organizations where id = ?", foreignId);
        }
    }
}
