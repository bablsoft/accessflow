package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQueryDryRunServiceTest {

    private final QueryParser queryParser = mock(QueryParser.class);
    private final DatasourceAdminService datasourceAdminService = mock(DatasourceAdminService.class);
    private final DatasourceUserPermissionLookupService permissionLookupService =
            mock(DatasourceUserPermissionLookupService.class);
    private final com.bablsoft.accessflow.core.api.RowSecurityResolutionService rowSecurityResolutionService =
            mock(com.bablsoft.accessflow.core.api.RowSecurityResolutionService.class);
    private final QueryExecutor queryExecutor = mock(QueryExecutor.class);
    private final MessageSource messageSource = mock(MessageSource.class);

    private final DefaultQueryDryRunService service = new DefaultQueryDryRunService(
            queryParser, datasourceAdminService, permissionLookupService,
            rowSecurityResolutionService, queryExecutor, messageSource);

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private DatasourceView view() {
        return new DatasourceView(datasourceId, orgId, "ds", DbType.POSTGRESQL, "h", 5432, "db",
                "u", com.bablsoft.accessflow.core.api.SslMode.DISABLE, 10, 1000, false, false, null,
                false, null, false, null, "postgresql", null, null, null, true,
                java.time.Instant.EPOCH);
    }

    private SqlParseResult parse(QueryType type, Set<String> tables) {
        return new SqlParseResult(type, false, List.of("SELECT 1"), tables, true, false);
    }

    private DatasourceUserPermissionView permission(boolean read, List<String> schemas,
                                                    List<String> tables) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, read,
                false, false, false, schemas, tables, List.of(), null);
    }

    @Test
    void adminPathUsesAdminLookupAndReturnsResult() {
        when(datasourceAdminService.getForAdmin(datasourceId, orgId)).thenReturn(view());
        when(queryParser.parse(anyString(), any())).thenReturn(parse(QueryType.SELECT, Set.of("users")));
        when(rowSecurityResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());
        var expected = QueryDryRunResult.of("postgresql", QueryType.SELECT, 5L, null, "{}",
                Set.of(), Duration.ZERO);
        when(queryExecutor.dryRun(any())).thenReturn(expected);

        var result = service.dryRun(datasourceId, "SELECT * FROM users", userId, orgId, true);

        assertThat(result).isSameAs(expected);
        verify(datasourceAdminService).getForAdmin(datasourceId, orgId);
    }

    @Test
    void nonAdminWithCapabilityAndEmptyAllowListPasses() {
        when(datasourceAdminService.getForUser(datasourceId, orgId, userId)).thenReturn(view());
        when(queryParser.parse(anyString(), any())).thenReturn(parse(QueryType.SELECT, Set.of("users")));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(java.util.Optional.of(permission(true, List.of(), List.of())));
        when(rowSecurityResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());
        var expected = QueryDryRunResult.of("postgresql", QueryType.SELECT, 1L, null, null,
                Set.of(), Duration.ZERO);
        when(queryExecutor.dryRun(any())).thenReturn(expected);

        var result = service.dryRun(datasourceId, "SELECT * FROM users", userId, orgId, false);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void nonAdminWithoutCapabilityIsDenied() {
        when(datasourceAdminService.getForUser(datasourceId, orgId, userId)).thenReturn(view());
        when(queryParser.parse(anyString(), any())).thenReturn(parse(QueryType.SELECT, Set.of("users")));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(java.util.Optional.of(permission(false, List.of(), List.of())));

        assertThatThrownBy(() -> service.dryRun(datasourceId, "SELECT 1", userId, orgId, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nonAdminWithNoPermissionIsDenied() {
        when(datasourceAdminService.getForUser(datasourceId, orgId, userId)).thenReturn(view());
        when(queryParser.parse(anyString(), any())).thenReturn(parse(QueryType.SELECT, Set.of("users")));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.dryRun(datasourceId, "SELECT 1", userId, orgId, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nonAdminReferencingDisallowedTableIsDenied() {
        when(datasourceAdminService.getForUser(datasourceId, orgId, userId)).thenReturn(view());
        when(queryParser.parse(anyString(), any())).thenReturn(parse(QueryType.SELECT, Set.of("users")));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(java.util.Optional.of(permission(true, List.of(), List.of("orders"))));
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("table not allowed");

        assertThatThrownBy(() -> service.dryRun(datasourceId, "SELECT * FROM users", userId, orgId, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unsupportedResultGetsLocalizedReason() {
        when(datasourceAdminService.getForAdmin(datasourceId, orgId)).thenReturn(view());
        when(queryParser.parse(anyString(), any())).thenReturn(parse(QueryType.SELECT, Set.of()));
        when(rowSecurityResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.unsupported("redis"));
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("not supported for redis");

        var result = service.dryRun(datasourceId, "GET foo", userId, orgId, true);

        assertThat(result.supported()).isFalse();
        assertThat(result.unsupportedReason()).isEqualTo("not supported for redis");
    }

    @Test
    void resolvedRowSecurityDirectivesArePassedToExecutor() {
        when(datasourceAdminService.getForAdmin(datasourceId, orgId)).thenReturn(view());
        when(queryParser.parse(anyString(), any())).thenReturn(parse(QueryType.SELECT, Set.of("t")));
        var policyId = UUID.randomUUID();
        when(rowSecurityResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(new ResolvedRowSecurityPredicate(policyId, "t", "region",
                        RowSecurityOperator.EQUALS, List.of("EU"))));
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.of("postgresql",
                QueryType.SELECT, 1L, null, null, Set.of(), Duration.ZERO));

        service.dryRun(datasourceId, "SELECT * FROM t", userId, orgId, true);

        var captor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).dryRun(captor.capture());
        assertThat(captor.getValue().rowSecurityPredicates()).hasSize(1);
        assertThat(captor.getValue().rowSecurityPredicates().getFirst().policyId()).isEqualTo(policyId);
    }
}
