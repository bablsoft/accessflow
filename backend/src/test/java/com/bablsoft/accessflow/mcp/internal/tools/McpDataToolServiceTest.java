package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditLogView;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.TableNotFoundException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpColumnSamples;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.proxy.api.SampleDataService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpDataToolServiceTest {

    @Mock DatasourceAdminService datasourceAdminService;
    @Mock QueryParser queryParser;
    @Mock SampleDataService sampleDataService;
    @Mock AuditLogService auditLogService;

    McpDataToolService tools;
    UUID userId;
    UUID orgId;

    @BeforeEach
    void setUp() {
        var currentUser = new McpCurrentUser();
        tools = new McpDataToolService(currentUser, datasourceAdminService, queryParser,
                sampleDataService, auditLogService);
        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        authenticateAs(UserRoleType.ANALYST);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- validate_sql ---------------------------------------------------------------------------

    @Test
    void validate_sql_valid_reports_query_type_and_unknown_tables() {
        var dsId = UUID.randomUUID();
        var sql = "SELECT * FROM users u JOIN ghost g ON g.id = u.id WHERE u.id = 1";
        when(datasourceAdminService.getForUser(dsId, orgId, userId)).thenReturn(newDatasourceView());
        when(queryParser.parse(sql, DbType.POSTGRESQL)).thenReturn(new SqlParseResult(
                QueryType.SELECT, false, List.of(sql), Set.of("users", "ghost"), true, false));
        when(datasourceAdminService.introspectSchema(dsId, orgId, userId, false))
                .thenReturn(schemaWithUsersAndOrders());

        var result = tools.validateSql(dsId, sql);

        assertThat(result.valid()).isTrue();
        assertThat(result.parseError()).isNull();
        assertThat(result.queryType()).isEqualTo("SELECT");
        assertThat(result.referencedTables()).containsExactly("ghost", "users");
        assertThat(result.hasWhereClause()).isTrue();
        assertThat(result.hasLimitClause()).isFalse();
        assertThat(result.schemaChecked()).isTrue();
        assertThat(result.unknownTables()).containsExactly("ghost");
    }

    @Test
    void validate_sql_invalid_returns_parse_error_without_introspecting() {
        var dsId = UUID.randomUUID();
        when(datasourceAdminService.getForUser(dsId, orgId, userId)).thenReturn(newDatasourceView());
        when(queryParser.parse(any(), any())).thenThrow(new InvalidSqlException("Unexpected token FROMM"));

        var result = tools.validateSql(dsId, "SELECT 1 FROMM dual");

        assertThat(result.valid()).isFalse();
        assertThat(result.parseError()).contains("FROMM");
        assertThat(result.queryType()).isNull();
        assertThat(result.referencedTables()).isEmpty();
        assertThat(result.unknownTables()).isEmpty();
        assertThat(result.schemaChecked()).isFalse();
        verify(datasourceAdminService, never()).introspectSchema(any(), any(), any(), anyBoolean());
    }

    @Test
    void validate_sql_degrades_when_introspection_unavailable() {
        var dsId = UUID.randomUUID();
        var sql = "SELECT * FROM users";
        when(datasourceAdminService.getForUser(dsId, orgId, userId)).thenReturn(newDatasourceView());
        when(queryParser.parse(sql, DbType.POSTGRESQL)).thenReturn(new SqlParseResult(
                QueryType.SELECT, false, List.of(sql), Set.of("users"), false, false));
        when(datasourceAdminService.introspectSchema(dsId, orgId, userId, false))
                .thenThrow(new DatasourceConnectionTestException("connection refused"));

        var result = tools.validateSql(dsId, sql);

        assertThat(result.valid()).isTrue();
        assertThat(result.schemaChecked()).isFalse();
        assertThat(result.unknownTables()).isEmpty();
    }

    @Test
    void validate_sql_uses_admin_lookup_for_admin() {
        authenticateAs(UserRoleType.ADMIN);
        var dsId = UUID.randomUUID();
        var sql = "SELECT * FROM users";
        when(datasourceAdminService.getForAdmin(dsId, orgId)).thenReturn(newDatasourceView());
        when(queryParser.parse(sql, DbType.POSTGRESQL)).thenReturn(new SqlParseResult(
                QueryType.SELECT, false, List.of(sql), Set.of("users"), false, false));
        when(datasourceAdminService.introspectSchema(dsId, orgId, userId, true))
                .thenReturn(schemaWithUsersAndOrders());

        var result = tools.validateSql(dsId, sql);

        assertThat(result.valid()).isTrue();
        assertThat(result.unknownTables()).isEmpty();
        verify(datasourceAdminService).getForAdmin(dsId, orgId);
        verify(datasourceAdminService, never()).getForUser(any(), any(), any());
    }

    @Test
    void validate_sql_propagates_datasource_not_found() {
        var dsId = UUID.randomUUID();
        when(datasourceAdminService.getForUser(dsId, orgId, userId))
                .thenThrow(new DatasourceNotFoundException(dsId));

        assertThatThrownBy(() -> tools.validateSql(dsId, "SELECT 1"))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    // --- get_column_samples ---------------------------------------------------------------------

    @Test
    void get_column_samples_maps_result_and_preserves_masking() {
        var dsId = UUID.randomUUID();
        var result = new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4", false),
                        new ResultColumn("ssn", 12, "varchar", true)),
                List.of(List.of(1, "***")),
                1L, false, Duration.ofMillis(7));
        when(sampleDataService.sample(dsId, orgId, userId, false, "public", "users", 50))
                .thenReturn(result);

        var samples = tools.getColumnSamples(dsId, "public", "users", null);

        assertThat(samples.columns()).extracting(McpColumnSamples.Column::name)
                .containsExactly("id", "ssn");
        assertThat(samples.columns().get(0).restricted()).isFalse();
        assertThat(samples.columns().get(1).restricted()).isTrue();
        assertThat(samples.rows()).containsExactly(List.of(1, "***"));
        assertThat(samples.rowCount()).isEqualTo(1L);
        assertThat(samples.truncated()).isFalse();
        assertThat(samples.durationMs()).isEqualTo(7L);
        verify(sampleDataService).sample(dsId, orgId, userId, false, "public", "users", 50);
    }

    @Test
    void get_column_samples_clamps_limit_and_passes_null_schema() {
        var dsId = UUID.randomUUID();
        when(sampleDataService.sample(any(), any(), any(), anyBoolean(), any(), any(), anyInt()))
                .thenReturn(emptyResult());

        tools.getColumnSamples(dsId, null, "t", 5000);
        tools.getColumnSamples(dsId, null, "t", 0);

        var limit = ArgumentCaptor.forClass(Integer.class);
        verify(sampleDataService, org.mockito.Mockito.times(2))
                .sample(any(), any(), any(), anyBoolean(), any(), any(), limit.capture());
        assertThat(limit.getAllValues()).containsExactly(200, 1);
    }

    @Test
    void get_column_samples_propagates_table_not_found() {
        var dsId = UUID.randomUUID();
        when(sampleDataService.sample(any(), any(), any(), anyBoolean(), any(), any(), anyInt()))
                .thenThrow(new TableNotFoundException(dsId, "ghost"));

        assertThatThrownBy(() -> tools.getColumnSamples(dsId, null, "ghost", 10))
                .isInstanceOf(TableNotFoundException.class);
    }

    // --- get_audit_log --------------------------------------------------------------------------

    @Test
    void get_audit_log_forces_actor_to_caller_and_maps_entries() {
        var withType = new AuditLogView(UUID.randomUUID(), orgId, userId, AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST, UUID.randomUUID(), Map.of("k", "v"),
                "10.0.0.1", "curl/8", Instant.now());
        var withoutType = new AuditLogView(UUID.randomUUID(), orgId, userId, AuditAction.USER_LOGIN,
                null, null, null, null, null, Instant.now());
        when(auditLogService.query(any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(withType, withoutType), 0, 20, 2, 1));

        var page = tools.getAuditLog("query_submitted", "query_request", null, null, 0, 20);

        var filter = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(auditLogService).query(org.mockito.ArgumentMatchers.eq(orgId), filter.capture(), any());
        assertThat(filter.getValue().actorId()).isEqualTo(userId);
        assertThat(filter.getValue().action()).isEqualTo(AuditAction.QUERY_SUBMITTED);
        assertThat(filter.getValue().resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).action()).isEqualTo("QUERY_SUBMITTED");
        assertThat(page.items().get(0).resourceType()).isEqualTo("query_request");
        assertThat(page.items().get(0).metadata()).containsEntry("k", "v");
        assertThat(page.items().get(1).resourceType()).isNull();
    }

    @Test
    void get_audit_log_accepts_resource_type_enum_name() {
        when(auditLogService.query(any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        tools.getAuditLog(null, "QUERY_REQUEST", null, null, null, null);

        var filter = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(auditLogService).query(any(), filter.capture(), any());
        assertThat(filter.getValue().resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
    }

    @Test
    void get_audit_log_parses_instant_window_and_clamps_page() {
        when(auditLogService.query(any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

        tools.getAuditLog(null, null, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", -5, 5000);

        var filter = ArgumentCaptor.forClass(AuditLogQuery.class);
        var pageReq = ArgumentCaptor.forClass(PageRequest.class);
        verify(auditLogService).query(any(), filter.capture(), pageReq.capture());
        assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(pageReq.getValue().page()).isZero();
        assertThat(pageReq.getValue().size()).isEqualTo(100);
    }

    @Test
    void get_audit_log_rejects_unknown_action() {
        assertThatThrownBy(() -> tools.getAuditLog("NOT_AN_ACTION", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_audit_log_rejects_unknown_resource_type() {
        assertThatThrownBy(() -> tools.getAuditLog(null, "not_a_type", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_audit_log_rejects_invalid_instant() {
        assertThatThrownBy(() -> tools.getAuditLog(null, null, "yesterday", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers --------------------------------------------------------------------------------

    private void authenticateAs(UserRoleType role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                new JwtClaims(userId, "user@example.com", role, orgId),
                null,
                "ROLE_" + role.name()));
    }

    private DatasourceView newDatasourceView() {
        return new DatasourceView(UUID.randomUUID(), orgId, "prod", DbType.POSTGRESQL,
                "localhost", 5432, "appdb", "app", SslMode.DISABLE,
                10, 10000, true, true, null, false, null, false, null, null, null,
                null, null, true, Instant.now());
    }

    private DatabaseSchemaView schemaWithUsersAndOrders() {
        return new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public", List.of(
                new DatabaseSchemaView.Table("users", List.of(), List.of()),
                new DatabaseSchemaView.Table("orders", List.of(), List.of())))));
    }

    private SelectExecutionResult emptyResult() {
        return new SelectExecutionResult(List.of(), List.of(), 0L, false, Duration.ZERO);
    }
}
