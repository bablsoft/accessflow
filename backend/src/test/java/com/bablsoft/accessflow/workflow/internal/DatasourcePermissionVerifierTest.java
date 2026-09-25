package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ColumnReference;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryShape;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourcePermissionVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock MessageSource messageSource;

    private DatasourcePermissionVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new DatasourcePermissionVerifier(permissionLookupService, messageSource,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DatasourceUserPermissionView permission(boolean canRead, boolean canWrite,
                                                    List<String> allowedTables,
                                                    Instant expiresAt) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                canRead, canWrite, false, false, null, allowedTables, null, null, List.of(), List.of(), List.of(), null, expiresAt);
    }

    @Test
    void verifyPassesWhenPermissionGrantsCapabilityAndNoAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, false, null, null)));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of("public.users"))))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsWhenNoPermissionExists() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyThrowsWhenPermissionIsExpired() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, null, NOW.minusSeconds(1))));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyPassesWhenPermissionExpiresInTheFuture() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, null, NOW.plus(Duration.ofHours(1)))));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsWhenSelectLacksReadCapability() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(false, true, null, null)));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyPassesWhenInsertHasWriteCapability() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(false, true, null, null)));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.INSERT,
                parsed(Set.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsLocalizedMessageWhenReferencedTableOutsideAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, List.of("public.users"), null)));
        when(messageSource.getMessage(eq("error.permission.table_not_allowed"), any(),
                any(Locale.class)))
                .thenReturn("TABLE_NOT_ALLOWED_MARKER");

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of("public.orders"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("TABLE_NOT_ALLOWED_MARKER");
    }

    @Test
    void verifyPassesWhenNoTablesReferencedDespiteAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(
                        permission(true, false, List.of("public.users"), null)));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsLocalizedMessageWhenDeniedColumnReferenced() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of("public.customer.national_id"))));
        when(messageSource.getMessage(eq("error.permission.column_not_allowed"), any(),
                any(Locale.class)))
                .thenReturn("COLUMN_DENIED_MARKER");
        var parsed = new SqlParseResult(QueryType.SELECT, false, List.of("sql"),
                Set.of("customer"), false, false,
                Set.of(new ColumnReference(Set.of("customer"), "national_id")), true);

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT, parsed))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("COLUMN_DENIED_MARKER");
    }

    @Test
    void verifyThrowsWhenWildcardReachesDeniedColumnsTable() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of("customer.national_id"))));
        var parsed = new SqlParseResult(QueryType.SELECT, false, List.of("sql"),
                Set.of("public.customer"), false, false,
                Set.of(ColumnReference.wildcard(Set.of("public.customer"))), true);

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT, parsed))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyFailsClosedWhenColumnsWereNotAnalyzed() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of("customer.national_id"))));
        var unanalyzed = new SqlParseResult(QueryType.SELECT, false, List.of("sql"),
                Set.of("customer"));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                unanalyzed))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyPassesWhenDeniedColumnIsNotReferenced() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of("customer.national_id"))));
        var parsed = new SqlParseResult(QueryType.SELECT, false, List.of("sql"),
                Set.of("customer"), false, false,
                Set.of(new ColumnReference(Set.of("customer"), "name")), true);

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT, parsed))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyThrowsLocalizedMessageWhenDeniedTableReferenced() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingTables(List.of("crm"), List.of(),
                        List.of("crm.salary"))));
        when(messageSource.getMessage(eq("error.permission.table_denied"),
                eq(new Object[]{"crm.salary"}), any(Locale.class)))
                .thenReturn("TABLE_DENIED_MARKER");

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of("crm.salary", "crm.customer"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("TABLE_DENIED_MARKER");
    }

    @Test
    void verifyPassesForAnAllowedSiblingOfADeniedTable() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingTables(List.of("crm"), List.of(),
                        List.of("crm.salary"))));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of("crm.customer", "crm.new_table"))))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyRejectsADeniedTableWithoutAnyAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingTables(List.of(), List.of(),
                        List.of("crm.salary"))));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of("crm.salary"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyRejectsATableInADeniedSchema() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingTables(List.of(), List.of("hr"), List.of())));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of("hr.payroll"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyReportsTheAllowListBeforeTheDenyList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingTables(List.of("crm"), List.of(),
                        List.of("crm.salary"))));
        when(messageSource.getMessage(eq("error.permission.table_not_allowed"), any(),
                any(Locale.class)))
                .thenReturn("TABLE_NOT_ALLOWED_MARKER");

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                parsed(Set.of("crm.salary", "hr.payroll"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("TABLE_NOT_ALLOWED_MARKER");
    }

    @Test
    void verifyRejectsAQueryWithADeniedShape() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingShapes(List.of(QueryShape.JOIN, QueryShape.CTE))));
        when(messageSource.getMessage(eq("error.permission.shape_denied"), any(), any(Locale.class)))
                .thenAnswer(inv -> "SHAPE_DENIED " + ((Object[]) inv.getArgument(1))[0]);

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                shaped(Set.of(QueryShape.CTE, QueryShape.JOIN, QueryShape.AGGREGATE), true)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("SHAPE_DENIED JOIN, CTE");
    }

    @Test
    void verifyLetsAQueryWithoutADeniedShapeThrough() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingShapes(List.of(QueryShape.JOIN))));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                shaped(Set.of(QueryShape.AGGREGATE), true)))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyFailsClosedWhenTheShapeWasNotAnalyzed() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingShapes(List.of(QueryShape.JOIN))));

        assertThatThrownBy(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                shaped(Set.of(), false)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnanalyzedShapeIsIrrelevantWithoutADenyList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denyingShapes(List.of())));

        assertThatCode(() -> verifier.verify(userId, datasourceId, QueryType.SELECT,
                shaped(Set.of(), false)))
                .doesNotThrowAnyException();
    }

    private DatasourceUserPermissionView denyingShapes(List<QueryShape> deniedShapes) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                true, false, false, false, null, null, null, null, List.of(), List.of(),
                deniedShapes, null, null);
    }

    private static SqlParseResult shaped(Set<QueryShape> shapes, boolean analyzed) {
        return new SqlParseResult(QueryType.SELECT, false, List.of("sql"), Set.of("public.users"),
                false, false, Set.of(), true, shapes, analyzed);
    }

    private DatasourceUserPermissionView denyingTables(List<String> allowedSchemas,
                                                       List<String> deniedSchemas,
                                                       List<String> deniedTables) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                true, false, false, false, allowedSchemas, null, null, null, deniedSchemas,
                deniedTables, List.of(), null, null);
    }

    private DatasourceUserPermissionView denying(List<String> deniedColumns) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                true, false, false, false, null, null, null, deniedColumns, List.of(), List.of(), List.of(), null, null);
    }

    private static SqlParseResult parsed(Set<String> tables) {
        return new SqlParseResult(QueryType.SELECT, false, List.of("sql"), tables, false, false,
                Set.of(), true);
    }
}
