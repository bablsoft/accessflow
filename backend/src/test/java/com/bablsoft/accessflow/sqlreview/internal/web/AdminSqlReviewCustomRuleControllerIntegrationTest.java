package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminSqlReviewCustomRuleControllerIntegrationTest extends SqlReviewIntegrationTestSupport {

    private static final String BASE = "/api/v1/admin/sql-review-rules";
    private static final String CONDITION = """
            {"type":"and","children":[
              {"type":"query_type","any_of":["UPDATE"]},
              {"type":"referenced_table","globs":["billing.*"]},
              {"type":"has_where","expected":false}]}""";

    private DatasourceEntity production;
    private String adminToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        seedOrganization();
        var admin = saveUser("admin", UserRoleType.ADMIN, null);
        var analyst = saveUser("analyst", UserRoleType.ANALYST, null);
        production = saveDatasource("billing", DbType.POSTGRESQL, DatasourceEnvironment.PRODUCTION);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        cleanupOrganization();
    }

    private static String rule(String ruleId, String name, String condition) {
        return """
                {"rule_id":"%s","name":"%s","message":"{statement_type} on {tables} has no WHERE",
                 "category":"STATEMENT_SAFETY","default_severity":"WARN","condition":%s}"""
                .formatted(ruleId, name, condition);
    }

    private MvcTestResult send(String method, String uri, String token, String body) {
        var request = switch (method) {
            case "POST" -> mvc.post().uri(uri);
            case "PUT" -> mvc.put().uri(uri);
            case "DELETE" -> mvc.delete().uri(uri);
            default -> mvc.get().uri(uri);
        };
        request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private String create(String ruleId) {
        var result = send("POST", BASE, adminToken, rule(ruleId, "Unbounded billing update", CONDITION));
        assertThat(result).hasStatus(201);
        return JsonPathReader.read(result, "$.id");
    }

    private long customRuleRows() {
        var count = jdbcTemplate.queryForObject(
                "select count(*) from sql_review_custom_rules where organization_id = ?", Long.class, org.getId());
        return count == null ? 0 : count;
    }

    @Test
    void aCreatedRuleIsListedConfigurableAndEvaluatedThenDeleteRemovesItsConfigs() {
        var created = send("POST", BASE, adminToken, rule("custom_billing_update", "Unbounded billing update",
                CONDITION));
        assertThat(created).hasStatus(201);
        assertThat(created).hasHeader(HttpHeaders.LOCATION, "http://localhost" + BASE + "/"
                + JsonPathReader.read(created, "$.id"));
        assertThat(created).bodyJson().extractingPath("$.condition.children[2].type").asString()
                .isEqualTo("has_where");
        var id = JsonPathReader.read(created, "$.id");
        assertThat(auditRows("SQL_REVIEW_RULE_CREATED")).isEqualTo(1);

        var catalog = send("GET", "/api/v1/sql-review/rules", adminToken, null);
        assertThat(catalog).hasStatus(200);
        assertThat(catalog).bodyJson().extractingPath("$[?(@.rule_id == 'custom_billing_update')].custom")
                .asArray().containsExactly(true);

        var ruleset = send("POST", "/api/v1/admin/sql-review-rulesets", adminToken, """
                {"name":"Production %s","environment":"PRODUCTION",
                 "rules":[{"rule_id":"custom_billing_update","severity":"BLOCK"}]}""".formatted(suffix));
        assertThat(ruleset).hasStatus(201);

        var evaluation = send("POST", "/api/v1/sql-review/evaluate", adminToken, """
                {"datasource_id":"%s","sql":"UPDATE billing.invoices SET paid = true"}"""
                .formatted(production.getId()));
        assertThat(evaluation).hasStatus(200);
        assertThat(evaluation).bodyJson()
                .extractingPath("$.findings[?(@.rule_id == 'custom_billing_update')].severity")
                .asArray().containsExactly("BLOCK");
        assertThat(evaluation).bodyJson()
                .extractingPath("$.findings[?(@.rule_id == 'custom_billing_update')].message")
                .asArray().containsExactly("UPDATE on billing.invoices has no WHERE");

        assertThat(send("DELETE", BASE + "/" + id, adminToken, null)).hasStatus(204);
        var configs = jdbcTemplate.queryForObject("""
                select count(*) from sql_review_rule_configs c join sql_review_rulesets r on r.id = c.ruleset_id
                where r.organization_id = ? and c.rule_id = 'custom_billing_update'""", Long.class, org.getId());
        assertThat(configs).isZero();
        assertThat(customRuleRows()).isZero();
        assertThat(auditRows("SQL_REVIEW_RULE_DELETED")).isEqualTo(1);
        assertThat(send("GET", BASE + "/" + id, adminToken, null)).hasStatus(404);
    }

    @Test
    void getListAndUpdateRoundTripAndTheRuleIdIsImmutable() {
        var id = create("custom_billing_update");

        assertThat(send("GET", BASE, adminToken, null)).bodyJson().extractingPath("$[*].rule_id").asArray()
                .containsExactly("custom_billing_update");
        var updated = send("PUT", BASE + "/" + id, adminToken,
                rule("custom_billing_update", "Renamed", "{\"type\":\"has_limit\",\"expected\":false}"));
        assertThat(updated).hasStatus(200);
        assertThat(updated).bodyJson().extractingPath("$.name").asString().isEqualTo("Renamed");
        assertThat(updated).bodyJson().extractingPath("$.condition.type").asString().isEqualTo("has_limit");
        assertThat(auditRows("SQL_REVIEW_RULE_UPDATED")).isEqualTo(1);

        var renamed = send("PUT", BASE + "/" + id, adminToken, rule("custom_other_id", "x", CONDITION));
        assertThat(renamed).hasStatus(422);
        assertThat(renamed).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULE_INVALID");
        assertThat(send("PUT", BASE + "/" + UUID.randomUUID(), adminToken,
                rule("custom_billing_update", "x", CONDITION))).hasStatus(404);
    }

    @Test
    void errorsMapToTheDocumentedStatusCodes() {
        create("custom_billing_update");

        var duplicate = send("POST", BASE, adminToken, rule("custom_billing_update", "Again", CONDITION));
        assertThat(duplicate).hasStatus(409);
        assertThat(duplicate).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULE_CONFLICT");

        var malformed = send("POST", BASE, adminToken, rule("custom_bad_rule", "Bad", "{\"type\":\"nope\"}"));
        assertThat(malformed).hasStatus(422);
        assertThat(malformed).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULE_INVALID");

        var badRegex = send("POST", BASE, adminToken,
                rule("custom_bad_regex", "Bad", "{\"type\":\"sql_matches\",\"pattern\":\"(\",\"ignore_case\":true}"));
        assertThat(badRegex).hasStatus(422);

        var invalid = send("POST", BASE, adminToken, rule("no_prefix", "", CONDITION));
        assertThat(invalid).hasStatus(400);
        assertThat(invalid).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");

        var unreadable = send("POST", BASE, adminToken,
                rule("custom_abc", "x", CONDITION).replace("STATEMENT_SAFETY", "NOT_A_CATEGORY"));
        assertThat(unreadable).hasStatus(400);

        assertThat(send("GET", BASE, analystToken, null)).hasStatus(403);
        assertThat(send("DELETE", BASE + "/" + UUID.randomUUID(), adminToken, null)).hasStatus(404);
    }

    @Test
    void testRunsTheDraftAndPersistsNothing() {
        var auditBefore = auditRows();

        var result = send("POST", BASE + "/test", adminToken, """
                {"rule":%s,"sql":"UPDATE billing.invoices SET paid = true","dialect":"MYSQL"}"""
                .formatted(rule("custom_draft_rule", "Draft", CONDITION)));

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.findings[0].rule_id").asString()
                .isEqualTo("custom_draft_rule");
        assertThat(result).bodyJson().extractingPath("$.findings[0].severity").asString().isEqualTo("WARN");
        assertThat(result).bodyJson().extractingPath("$.findings[0].message").asString()
                .isEqualTo("UPDATE on billing.invoices has no WHERE");
        assertThat(customRuleRows()).isZero();
        assertThat(auditRows()).isEqualTo(auditBefore);

        var unparseable = send("POST", BASE + "/test", adminToken, """
                {"rule":%s,"sql":"UPDATE WHERE"}""".formatted(rule("custom_draft_rule", "Draft", CONDITION)));
        assertThat(unparseable).hasStatus(422);
        var plugin = send("POST", BASE + "/test", adminToken, """
                {"rule":%s,"sql":"SELECT 1","dialect":"MONGODB"}""".formatted(rule("custom_draft_rule", "D", CONDITION)));
        assertThat(plugin).hasStatus(422);
        assertThat(plugin).bodyJson().extractingPath("$.error").asString().isEqualTo("SQL_REVIEW_RULE_INVALID");
    }

    /** Reads one string from a JSON response body without a second assertion chain. */
    private static final class JsonPathReader {
        static String read(MvcTestResult result, String path) {
            try {
                return JsonPath.read(result.getResponse().getContentAsString(), path);
            } catch (UnsupportedEncodingException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
