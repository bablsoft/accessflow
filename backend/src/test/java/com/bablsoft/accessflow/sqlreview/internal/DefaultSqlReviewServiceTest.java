package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRuleConfigEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRulesetEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRuleConfigRepository;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRulesetRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSqlReviewServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DATASOURCE = UUID.randomUUID();

    @Mock
    private DatasourceAdminService datasourceAdminService;
    @Mock
    private SqlParserService sqlParserService;
    @Mock
    private SqlReviewRulesetRepository rulesetRepository;
    @Mock
    private SqlReviewRuleConfigRepository ruleConfigRepository;

    private DefaultSqlReviewService service;

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        messages.addMessage("error.sql_review_rule_params_invalid", java.util.Locale.getDefault(), "malformed");
        service = new DefaultSqlReviewService(datasourceAdminService, sqlParserService, rulesetRepository,
                ruleConfigRepository, new SqlRuleCatalog(), new SqlRuleParamsCodec(new ObjectMapper(), messages));
    }

    private static DatasourceView datasource(DbType dbType, DatasourceEnvironment environment) {
        return new DatasourceView(DATASOURCE, ORG, "ds", dbType, "localhost", 5432, "db", "user", SslMode.DISABLE,
                5, 1000, false, false, null, false, null, false, null, null, null, List.of(), true,
                Instant.now(), null, false, null, environment);
    }

    private static SqlReviewRulesetEntity ruleset(DatasourceEnvironment environment, boolean enabled) {
        var entity = new SqlReviewRulesetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setName(environment == null ? "default" : environment.name());
        entity.setEnvironment(environment);
        entity.setEnabled(enabled);
        return entity;
    }

    private static SqlReviewRuleConfigEntity config(SqlReviewRulesetEntity ruleset, String ruleId,
                                                    SqlReviewSeverity severity, String params) {
        var entity = new SqlReviewRuleConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setRuleset(ruleset);
        entity.setRuleId(ruleId);
        entity.setSeverity(severity);
        entity.setParams(params);
        return entity;
    }

    private void givenSingleStatement(String sql) {
        when(sqlParserService.parse(sql)).thenReturn(new SqlParseResult(QueryType.DELETE, sql));
    }

    @Test
    void nonRelationalEngineIsNotApplicableAndTouchesNothing() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG))
                .thenReturn(datasource(DbType.MONGODB, DatasourceEnvironment.PRODUCTION));

        var result = service.evaluate(ORG, DATASOURCE, "{\"find\": \"users\"}");

        assertThat(result.applicable()).isFalse();
        assertThat(result.findings()).isEmpty();
        assertThat(result.hasBlocking()).isFalse();
        verifyNoInteractions(sqlParserService, rulesetRepository, ruleConfigRepository);
    }

    @Test
    void everyPluginEngineIsNotApplicableAndEveryRelationalDialectIs() {
        for (DbType dbType : DbType.values()) {
            assertThat(DefaultSqlReviewService.RELATIONAL_DIALECTS.contains(dbType)).as(dbType.name())
                    .isEqualTo(Set.of(DbType.POSTGRESQL, DbType.MYSQL, DbType.MARIADB, DbType.ORACLE,
                            DbType.MSSQL, DbType.CUSTOM).contains(dbType));
        }
    }

    @Test
    void environmentRulesetWinsOverTheOrgDefault() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG))
                .thenReturn(datasource(DbType.POSTGRESQL, DatasourceEnvironment.PRODUCTION));
        givenSingleStatement("DELETE FROM t");
        var production = ruleset(DatasourceEnvironment.PRODUCTION, true);
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.PRODUCTION))
                .thenReturn(Optional.of(production));
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(production.getId())).thenReturn(List.of(
                config(production, "missing_where_on_delete", SqlReviewSeverity.WARN, null),
                config(production, "dml_without_transaction", SqlReviewSeverity.OFF, null)));

        var result = service.evaluate(ORG, DATASOURCE, "DELETE FROM t");

        assertThat(result.applicable()).isTrue();
        assertThat(result.findings()).extracting(SqlReviewFinding::ruleId, SqlReviewFinding::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("missing_where_on_delete", SqlReviewSeverity.WARN));
        verify(rulesetRepository, never()).findByOrganizationIdAndEnvironmentIsNull(any());
    }

    @Test
    void unboundEnvironmentAndNullEnvironmentFallToTheOrgDefaultWithBuiltInSeverities() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG))
                .thenReturn(datasource(DbType.MYSQL, DatasourceEnvironment.STAGING))
                .thenReturn(datasource(DbType.CUSTOM, null));
        givenSingleStatement("DELETE FROM t");
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.STAGING))
                .thenReturn(Optional.empty());
        var fallback = ruleset(null, true);
        when(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(ORG)).thenReturn(Optional.of(fallback));
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(fallback.getId())).thenReturn(List.of());

        var staging = service.evaluate(ORG, DATASOURCE, "DELETE FROM t");
        var unassigned = service.evaluate(ORG, DATASOURCE, "DELETE FROM t");

        for (var result : List.of(staging, unassigned)) {
            assertThat(result.applicable()).isTrue();
            assertThat(result.findings()).extracting(SqlReviewFinding::ruleId, SqlReviewFinding::severity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("dml_without_transaction", SqlReviewSeverity.WARN),
                            org.assertj.core.groups.Tuple.tuple("missing_where_on_delete", SqlReviewSeverity.BLOCK));
            assertThat(result.hasBlocking()).isTrue();
        }
        verify(rulesetRepository, never()).findByOrganizationIdAndEnvironment(ORG, null);
    }

    @Test
    void noRulesetAtAllIsCleanButStillParses() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG)).thenReturn(datasource(DbType.ORACLE, null));
        givenSingleStatement("DELETE FROM t");
        when(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(ORG)).thenReturn(Optional.empty());

        var result = service.evaluate(ORG, DATASOURCE, "DELETE FROM t");

        assertThat(result.applicable()).isTrue();
        assertThat(result.findings()).isEmpty();
        verify(sqlParserService).parse("DELETE FROM t");
        verifyNoInteractions(ruleConfigRepository);
    }

    @Test
    void disabledBoundRulesetYieldsNoRulesAndDoesNotFallThrough() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG))
                .thenReturn(datasource(DbType.MSSQL, DatasourceEnvironment.PRODUCTION));
        givenSingleStatement("DELETE FROM t");
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.PRODUCTION))
                .thenReturn(Optional.of(ruleset(DatasourceEnvironment.PRODUCTION, false)));

        var result = service.evaluate(ORG, DATASOURCE, "DELETE FROM t");

        assertThat(result.applicable()).isTrue();
        assertThat(result.findings()).isEmpty();
        verify(rulesetRepository, never()).findByOrganizationIdAndEnvironmentIsNull(any());
        verifyNoInteractions(ruleConfigRepository);
    }

    @Test
    void configRowsOverrideSeverityAndSupplyParamsWhileUnknownRowsAreIgnored() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG)).thenReturn(datasource(DbType.MARIADB, null));
        var sql = "SELECT sleep(1) FROM payroll.salaries LIMIT 1";
        when(sqlParserService.parse(sql)).thenReturn(new SqlParseResult(QueryType.SELECT, sql));
        var fallback = ruleset(null, true);
        when(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(ORG)).thenReturn(Optional.of(fallback));
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(fallback.getId())).thenReturn(List.of(
                config(fallback, "disallowed_function", SqlReviewSeverity.WARN, "{\"names\": [\"now\"]}"),
                config(fallback, "protected_table", SqlReviewSeverity.BLOCK, "{\"globs\": [\"payroll.*\"]}"),
                config(fallback, "not_a_rule", SqlReviewSeverity.BLOCK, null)));

        var result = service.evaluate(ORG, DATASOURCE, sql);

        // sleep() is no longer banned (names overridden), payroll.* is protected, select_star etc. untouched.
        assertThat(result.findings()).extracting(SqlReviewFinding::ruleId, SqlReviewFinding::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("protected_table", SqlReviewSeverity.BLOCK));
        assertThat(result.findings().get(0).args()).containsEntry("table", "payroll.salaries");
    }

    @Test
    void envelopeStatementsAreEvaluatedIndividually() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG)).thenReturn(datasource(DbType.POSTGRESQL, null));
        var sql = "BEGIN; DELETE FROM a; DELETE FROM b WHERE id = 1; COMMIT;";
        when(sqlParserService.parse(sql)).thenReturn(new SqlParseResult(QueryType.DELETE, true,
                List.of("DELETE FROM a", "DELETE FROM b WHERE id = 1"), Set.of("a", "b")));
        var fallback = ruleset(null, true);
        when(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(ORG)).thenReturn(Optional.of(fallback));
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(fallback.getId())).thenReturn(List.of());

        var result = service.evaluate(ORG, DATASOURCE, sql);

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("missing_where_on_delete");
            assertThat(f.statementIndex()).isZero();
            assertThat(f.lineNumber()).isNull();
        });
    }

    @Test
    void datasourceNotFoundPropagates() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG)).thenThrow(new DatasourceNotFoundException(DATASOURCE));

        assertThatThrownBy(() -> service.evaluate(ORG, DATASOURCE, "SELECT 1"))
                .isInstanceOf(DatasourceNotFoundException.class);
        verifyNoInteractions(sqlParserService, rulesetRepository);
    }

    @Test
    void unparseableSqlPropagates() {
        when(datasourceAdminService.getForAdmin(DATASOURCE, ORG)).thenReturn(datasource(DbType.POSTGRESQL, null));
        when(sqlParserService.parse(anyString())).thenThrow(new InvalidSqlException("bad"));

        assertThatThrownBy(() -> service.evaluate(ORG, DATASOURCE, "not sql"))
                .isInstanceOf(InvalidSqlException.class);
        verifyNoInteractions(rulesetRepository);
    }
}
