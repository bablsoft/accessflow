package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.QuerySqlReviewFindingRepository;
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
class SqlReviewControllerIntegrationTest extends SqlReviewIntegrationTestSupport {

    @Autowired QuerySqlReviewFindingRepository findingRepository;

    private DatasourceEntity production;
    private DatasourceEntity mongo;
    private String adminToken;
    private String readerToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() {
        seedOrganization();
        var admin = saveUser("admin", UserRoleType.ADMIN, null);
        var reader = saveUser("reader", UserRoleType.ANALYST, null);
        var outsider = saveUser("outsider", UserRoleType.ANALYST, null);
        production = saveDatasource("payments", DbType.POSTGRESQL, DatasourceEnvironment.PRODUCTION);
        mongo = saveDatasource("events", DbType.MONGODB, DatasourceEnvironment.PRODUCTION);
        grantRead(reader, production, admin);
        grantRead(reader, mongo, admin);
        adminToken = generateToken(admin);
        readerToken = generateToken(reader);
        outsiderToken = generateToken(outsider);

        var rulesetId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into sql_review_rulesets (id, organization_id, name, environment, enabled)
                values (?, ?, ?, 'PRODUCTION'::datasource_environment, true)
                """, rulesetId, org.getId(), "Production " + suffix);
        jdbcTemplate.update("""
                insert into sql_review_rule_configs (id, ruleset_id, rule_id, severity, params)
                values (?, ?, 'protected_table', 'BLOCK'::sql_review_severity, '{"globs":["payroll.*"]}'::jsonb),
                       (?, ?, 'dml_without_transaction', 'OFF'::sql_review_severity, null)
                """, UUID.randomUUID(), rulesetId, UUID.randomUUID(), rulesetId);
    }

    @AfterEach
    void cleanup() {
        cleanupOrganization();
    }

    private MvcTestResult evaluate(String token, UUID datasourceId, String sql, String acceptLanguage) {
        var request = mvc.post().uri("/api/v1/sql-review/evaluate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"" + datasourceId + "\",\"sql\":" + quote(sql) + "}");
        if (acceptLanguage != null) {
            request = request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        }
        return request.exchange();
    }

    private static String quote(String sql) {
        return "\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void evaluateReturnsLocalizedFindingsAndPersistsNothing() {
        var findingsBefore = findingRepository.count();
        var auditBefore = auditRows();

        var result = evaluate(readerToken, production.getId(), "DELETE FROM payroll.salaries", "de");

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.applicable").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.findings[*].rule_id").asArray()
                .containsExactly("missing_where_on_delete", "protected_table");
        assertThat(result).bodyJson().extractingPath("$.findings[0].severity").asString().isEqualTo("BLOCK");
        assertThat(result).bodyJson().extractingPath("$.findings[0].statement_index").asNumber().isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.findings[0].line_number").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.findings[0].message").asString()
                .isEqualTo("DELETE auf payroll.salaries hat keine WHERE-Klausel und entfernt jede Zeile");
        assertThat(result).bodyJson().extractingPath("$.findings[1].message").asString()
                .isEqualTo("Die Anweisung greift auf die geschützte Tabelle payroll.salaries zu (entspricht payroll.*)");

        // Read-only: no query_sql_review_findings row, no audit_log row.
        assertThat(findingRepository.count()).isEqualTo(findingsBefore);
        assertThat(auditRows()).isEqualTo(auditBefore);
    }

    @Test
    void evaluateFallsBackToEnglishAndQueryAdminSeesEveryDatasource() {
        var result = evaluate(adminToken, production.getId(), "DELETE FROM payroll.salaries", null);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.findings[1].message").asString()
                .isEqualTo("The statement touches protected table payroll.salaries (matches payroll.*)");
    }

    @Test
    void aDatasourceTheCallerCannotSeeIsNotFoundNeverForbidden() {
        var result = evaluate(outsiderToken, production.getId(), "DELETE FROM payroll.salaries", null);

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("DATASOURCE_NOT_FOUND");
        assertThat(evaluate(readerToken, UUID.randomUUID(), "SELECT 1", null)).hasStatus(404);
    }

    @Test
    void unparseableSqlIs422NotAnEmptyCleanResult() {
        var result = evaluate(readerToken, production.getId(), "SELECT FROM", null);

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("INVALID_SQL");
        assertThat(evaluate(readerToken, production.getId(), " ", null)).hasStatus(400);

        var unreadable = mvc.post().uri("/api/v1/sql-review/evaluate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"not-a-uuid\",\"sql\":\"SELECT 1\"}").exchange();
        assertThat(unreadable).hasStatus(400);
        assertThat(unreadable).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void anEnginePluginDatasourceIsNotApplicable() {
        var result = evaluate(readerToken, mongo.getId(), "{\"find\": \"users\"}", null);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.applicable").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.findings.length()").asNumber().isEqualTo(0);
    }

    @Test
    void theCatalogIsLocalizedAndRequiresSqlReviewManage() {
        var forbidden = mvc.get().uri("/api/v1/sql-review/rules")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerToken).exchange();
        assertThat(forbidden).hasStatus(403);

        var result = mvc.get().uri("/api/v1/sql-review/rules")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "de").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.length()").asNumber().isEqualTo(14);
        assertThat(result).bodyJson().extractingPath("$[0].rule_id").asString().isEqualTo("select_star");
        assertThat(result).bodyJson().extractingPath("$[0].category").asString().isEqualTo("PERFORMANCE");
        assertThat(result).bodyJson().extractingPath("$[0].default_severity").asString().isEqualTo("WARN");
        assertThat(result).bodyJson().extractingPath("$[0].name").asString().isEqualTo("SELECT *");
        assertThat(result).bodyJson().extractingPath("$[0].params.length()").asNumber().isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$[?(@.rule_id=='protected_table')].params[0].key").asArray()
                .containsExactly("globs");
        assertThat(result).bodyJson().extractingPath("$[?(@.rule_id=='disallowed_function')].params[0].defaults[0]")
                .asArray().containsExactly("pg_sleep");
        assertThat(result).bodyJson().extractingPath("$[?(@.rule_id=='missing_where_on_delete')].description")
                .asArray().singleElement().asString().isNotBlank();
    }
}
