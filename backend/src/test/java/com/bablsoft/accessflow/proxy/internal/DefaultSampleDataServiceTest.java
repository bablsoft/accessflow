package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.TableNotFoundException;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSampleDataServiceTest {

    @Mock
    private DatasourceAdminService datasourceAdminService;
    @Mock
    private DatasourceUserPermissionLookupService permissionLookupService;
    @Mock
    private MaskingPolicyResolutionService maskingPolicyResolutionService;
    @Mock
    private RowSecurityResolutionService rowSecurityResolutionService;
    @Mock
    private com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService rowLimitPolicyResolutionService;
    @Mock
    private QueryExecutor queryExecutor;
    @Mock
    private org.springframework.context.MessageSource messageSource;

    @Mock
    private com.bablsoft.accessflow.core.api.DataBudgetStatusService dataBudgetStatusService;
    @Mock
    private com.bablsoft.accessflow.core.api.DataBudgetUsageService dataBudgetUsageService;
    @Mock
    private com.bablsoft.accessflow.audit.api.AuditLogService auditLogService;

    @InjectMocks
    private DefaultSampleDataService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private final SelectExecutionResult result = new SelectExecutionResult(
            List.of(), List.of(), 0, false, Duration.ZERO);

    @BeforeEach
    void setUp() {
        lenient().when(dataBudgetStatusService.statusFor(any(), any()))
                .thenAnswer(inv -> com.bablsoft.accessflow.core.api.DataBudgetStatus.none(
                        inv.getArgument(0)));
        var schemaView = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("Users", List.of(
                                new DatabaseSchemaView.Column("id", "uuid", false, true)),
                                List.of())))));
        lenient().when(datasourceAdminService.introspectSchema(eq(datasourceId), eq(organizationId),
                eq(userId), anyBoolean())).thenReturn(schemaView);
        lenient().when(queryExecutor.sampleTable(any())).thenReturn(result);
        lenient().when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void nonAdminIsRefusedAPreviewOfATableWithADeniedColumn() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.of(
                new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, true,
                        false, false, false, List.of(), List.of(), List.of(),
                        List.of("users.ssn"), List.of(), List.of(), List.of(), null, null)));
        when(messageSource.getMessage(eq("error.permission.column_not_allowed"), any(), any()))
                .thenReturn("column denied");

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "users", 10))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessage("column denied");
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminPreviewIsAllowedWhenTheDeniedColumnIsOnAnotherTable() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.of(
                new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, true,
                        false, false, false, List.of(), List.of(), List.of(),
                        List.of("public.orders.card"), List.of(), List.of(), List.of(), null, null)));

        assertThat(service.sample(datasourceId, organizationId, userId, false, "public", "users",
                10)).isSameAs(result);
    }

    @Test
    void adminSamplesWithoutPermissionRow() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        var out = service.sample(datasourceId, organizationId, userId, true, "public", "users", 50);

        assertThat(out).isSameAs(result);
        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        // Canonical names (DB casing) are taken from introspection, not the request.
        assertThat(captor.getValue().schema()).isEqualTo("public");
        assertThat(captor.getValue().table()).isEqualTo("Users");
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(50);
    }

    @Test
    void caseInsensitiveTableMatchResolvesCanonicalName() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        service.sample(datasourceId, organizationId, userId, true, "PUBLIC", "USERS", 10);

        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().table()).isEqualTo("Users");
        assertThat(captor.getValue().schema()).isEqualTo("public");
    }

    @Test
    void nullSchemaSearchesAllNamespaces() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        service.sample(datasourceId, organizationId, userId, true, null, "users", 10);

        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().table()).isEqualTo("Users");
    }

    @Test
    void resolvedDirectivesAreForwardedToExecutor() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of("public.users.ssn"),
                        List.of(), List.of())));
        var policyId = UUID.randomUUID();
        when(maskingPolicyResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(new ResolvedColumnMask(policyId, "public.users.email",
                        MaskingStrategy.EMAIL, Map.of())));
        var rlsId = UUID.randomUUID();
        when(rowSecurityResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(new ResolvedRowSecurityPredicate(rlsId, "public.users",
                        "region", RowSecurityOperator.EQUALS, List.of("EU"))));

        service.sample(datasourceId, organizationId, userId, false, "public", "users", 25);

        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        var req = captor.getValue();
        assertThat(req.restrictedColumns()).containsExactly("public.users.ssn");
        assertThat(req.columnMasks()).singleElement()
                .satisfies(m -> assertThat(m.columnRef()).isEqualTo("public.users.email"));
        assertThat(req.rowSecurityPredicates()).singleElement()
                .satisfies(p -> assertThat(p.values()).containsExactly("EU"));
    }

    @Test
    void tableAbsentFromSchemaIsNotFound() {
        lenient().when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, true,
                "public", "ghosts", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminWithoutPermissionRowIsNotFound() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "users", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminWithoutReadCapabilityIsNotFound() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(false, List.of(), List.of(), List.of())));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "users", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminTableOutsideAllowListIsNotFound() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(),
                        List.of("public.orders"))));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "users", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminTableWithinAllowedTablesIsPermitted() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(),
                        List.of("public.users"))));

        var out = service.sample(datasourceId, organizationId, userId, false, "public", "users", 50);

        assertThat(out).isSameAs(result);
    }

    @Test
    void nonAdminSchemaWithinAllowedSchemasIsPermitted() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of("public"), List.of())));

        var out = service.sample(datasourceId, organizationId, userId, false, "public", "users", 50);

        assertThat(out).isSameAs(result);
    }

    @Test
    void nonAdminDeniedTableIsNotFoundWithoutAnyAllowList() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of(), List.of(), List.of("public.users"))));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "users", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminDeniedTableIsNotFoundEvenInsideAnAllowedSchema() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of("public"), List.of(), List.of("users"))));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "users", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminTableInADeniedSchemaIsNotFound() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of(), List.of("public"), List.of())));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "users", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void nonAdminPreviewIsAllowedWhenTheDeniedTableIsAnother() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(denying(List.of("public"), List.of("hr"),
                        List.of("public.salary"))));

        assertThat(service.sample(datasourceId, organizationId, userId, false, "public", "users",
                50)).isSameAs(result);
    }

    @Test
    void rowLimitOverrideBelowTheRequestedLimitCapsThePreview() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(new DatasourceUserPermissionView(UUID.randomUUID(), userId,
                        datasourceId, true, false, false, false, List.of(), List.of(), List.of(), null, List.of(), List.of(),
                        List.of(),
                        5, null)));

        service.sample(datasourceId, organizationId, userId, false, "public", "users", 50);

        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(5);
    }

    @Test
    void rowLimitOverrideAboveTheRequestedLimitLeavesTheLimit() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(new DatasourceUserPermissionView(UUID.randomUUID(), userId,
                        datasourceId, true, false, false, false, List.of(), List.of(), List.of(), null, List.of(), List.of(),
                        List.of(),
                        500, null)));

        service.sample(datasourceId, organizationId, userId, false, "public", "users", 50);

        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(50);
    }

    @Test
    void rowLimitPolicyOnTheSampledTableCapsThePreview() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(new DatasourceUserPermissionView(UUID.randomUUID(), userId,
                        datasourceId, true, false, false, false, List.of(), List.of(), List.of(), null, List.of(), List.of(),
                        List.of(),
                        20, null)));
        when(rowLimitPolicyResolutionService.resolve(organizationId, datasourceId, userId,
                java.util.Set.of("public.users")))
                .thenReturn(Optional.of(new com.bablsoft.accessflow.core.api.AppliedRowLimit(
                        7, java.util.Set.of(UUID.randomUUID()))));

        service.sample(datasourceId, organizationId, userId, false, "public", "users", 50);

        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(7);
    }

    @Test
    void bareEntryDoesNotAdmitASchemaQualifiedTargetWhenTheNameIsAmbiguous() {
        // Defence in depth: even a view that still lists both tables must not admit either.
        stubSchemas(schema("public", "orders"), schema("archive", "orders"));
        stubCatalog(schema("public", "orders"), schema("archive", "orders"));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(), List.of("orders"))));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "archive", "orders", 50))
                .isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "orders", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void bareEntryAdmitsTheOnlyTableOfThatName() {
        stubSchemas(schema("archive", "orders"));
        stubCatalog(schema("archive", "orders"), schema("public", "customers"));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(), List.of("orders"))));

        assertThat(service.sample(datasourceId, organizationId, userId, false, "archive", "orders",
                50)).isSameAs(result);
    }

    @Test
    void bareEntryCountsTheUnfilteredCatalogNotTheCallersView() {
        // The filtered view shows archive.orders through a catalog-qualified grant and hides
        // public.orders; the bare entry must still see the name is ambiguous in the database.
        stubSchemas(schema("archive", "orders"));
        stubCatalog(schema("public", "orders"), schema("archive", "orders"));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(),
                        List.of("orders", "cat.archive.orders"))));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "archive", "orders", 50))
                .isInstanceOf(TableNotFoundException.class);
        verify(queryExecutor, never()).sampleTable(any());
    }

    @Test
    void coveredTargetNeverIntrospectsTheUnfilteredCatalog() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of("public"), List.of())));

        service.sample(datasourceId, organizationId, userId, false, "public", "users", 50);

        verify(datasourceAdminService, never()).introspectSchemaForSystem(any(), any());
    }

    @Test
    void qualifiedEntryDoesNotCoverTheSameNameInAnotherSchema() {
        stubSchemas(schema("public", "orders"), schema("archive", "orders"));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(),
                        List.of("public.orders"))));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "archive", "orders", 50))
                .isInstanceOf(TableNotFoundException.class);
        assertThat(service.sample(datasourceId, organizationId, userId, false, "public", "orders",
                50)).isSameAs(result);
    }

    @Test
    void allowedSchemaCoversAnAmbiguousNameInsideIt() {
        stubSchemas(schema("public", "orders"), schema("archive", "orders"));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of("archive"), List.of())));

        assertThat(service.sample(datasourceId, organizationId, userId, false, "archive", "orders",
                50)).isSameAs(result);
        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false,
                "public", "orders", 50))
                .isInstanceOf(TableNotFoundException.class);
    }

    @Test
    void quotedMixedCaseEntriesAreNormalizedLikeTheQueryGate() {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(),
                        List.of(" \"PUBLIC\".[Users] ", ""))));

        assertThat(service.sample(datasourceId, organizationId, userId, false, "public", "users",
                50)).isSameAs(result);
    }

    @Test
    void unnamedSchemaTargetIsCoveredOnlyByABareEntry() {
        stubSchemas(schema("", "orders"));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of(), List.of("orders"))));

        assertThat(service.sample(datasourceId, organizationId, userId, false, null, "orders", 50))
                .isSameAs(result);
        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().table()).isEqualTo("orders");
    }

    @Test
    void unnamedSchemaTargetIsRefusedWithOnlyAQualifiedEntry() {
        stubSchemas(schema("", "orders"));
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission(true, List.of(), List.of("public"),
                        List.of("public.orders"))));

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, false, null,
                "orders", 50))
                .isInstanceOf(TableNotFoundException.class);
    }

    private void stubCatalog(DatabaseSchemaView.Schema... schemas) {
        when(datasourceAdminService.introspectSchemaForSystem(datasourceId, organizationId))
                .thenReturn(new DatabaseSchemaView(List.of(schemas)));
    }

    private void stubSchemas(DatabaseSchemaView.Schema... schemas) {
        when(datasourceAdminService.introspectSchema(eq(datasourceId), eq(organizationId),
                eq(userId), anyBoolean())).thenReturn(new DatabaseSchemaView(List.of(schemas)));
    }

    private static DatabaseSchemaView.Schema schema(String name, String table) {
        return new DatabaseSchemaView.Schema(name, List.of(new DatabaseSchemaView.Table(table,
                List.of(new DatabaseSchemaView.Column("id", "uuid", false, true)), List.of())));
    }

    private DatasourceUserPermissionView permission(boolean canRead, List<String> restrictedColumns,
                                                    List<String> allowedSchemas,
                                                    List<String> allowedTables) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, canRead,
                false, false, false, allowedSchemas, allowedTables, restrictedColumns, null, List.of(), List.of(), List.of(), null, null);
    }

    private DatasourceUserPermissionView denying(List<String> allowedSchemas,
                                                 List<String> deniedSchemas,
                                                 List<String> deniedTables) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, true,
                false, false, false, allowedSchemas, List.of(), List.of(), null, deniedSchemas,
                deniedTables, List.of(), null, null);
    }

    @Test
    void exhaustedBudgetRefusesThePreviewWhateverTheAction() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());
        var budget = new com.bablsoft.accessflow.core.api.DataBudgetConsumption(UUID.randomUUID(),
                "Daily", 10L, null, 60,
                com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REQUIRE_REVIEW, null, 10, 0);
        when(dataBudgetStatusService.statusFor(datasourceId, userId)).thenReturn(
                new com.bablsoft.accessflow.core.api.DataBudgetStatus(datasourceId, "ds",
                        List.of(budget)));
        when(messageSource.getMessage(eq("error.data_budget.exhausted"), any(), any()))
                .thenReturn("used up");

        assertThatThrownBy(() -> service.sample(datasourceId, organizationId, userId, true,
                "public", "users", 50))
                .isInstanceOf(com.bablsoft.accessflow.core.api.DataBudgetExhaustedException.class)
                .hasMessage("used up");
        verify(queryExecutor, never()).sampleTable(any());
        var audit = ArgumentCaptor.forClass(com.bablsoft.accessflow.audit.api.AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action())
                .isEqualTo(com.bablsoft.accessflow.audit.api.AuditAction.QUERY_DATA_BUDGET_ENFORCED);
        assertThat(audit.getValue().metadata()).containsEntry("stage", "sample")
                .containsEntry("table", "public.users");
    }

    @Test
    void remainingAllowanceCapsThePreviewAndIsCharged() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());
        var budget = new com.bablsoft.accessflow.core.api.DataBudgetConsumption(UUID.randomUUID(),
                "Daily", 10L, 1_000_000L, 60,
                com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REJECT, null, 7, 0);
        when(dataBudgetStatusService.statusFor(datasourceId, userId)).thenReturn(
                new com.bablsoft.accessflow.core.api.DataBudgetStatus(datasourceId, "ds",
                        List.of(budget)));
        List<List<Object>> rows = List.of(List.of("a"), List.of("b"));
        when(queryExecutor.sampleTable(any())).thenReturn(
                new SelectExecutionResult(List.of(), rows, 2, false, Duration.ZERO));

        var out = service.sample(datasourceId, organizationId, userId, true, "public", "users", 50);

        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(3);
        assertThat(out.resultBytes()).isPositive();
        var usage = ArgumentCaptor.forClass(com.bablsoft.accessflow.core.api.DataBudgetUsageRecord.class);
        verify(dataBudgetUsageService).record(usage.capture());
        assertThat(usage.getValue().rowsRead()).isEqualTo(2);
        assertThat(usage.getValue().bytesRead()).isEqualTo(out.resultBytes());
        assertThat(usage.getValue().source())
                .isEqualTo(com.bablsoft.accessflow.core.api.DataBudgetUsageSource.SAMPLE_DATA);
    }

    @Test
    void aPreviewCutAtTheBudgetRowAllowanceIsAttributedToTheBudget() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());
        var budget = new com.bablsoft.accessflow.core.api.DataBudgetConsumption(UUID.randomUUID(),
                "Daily", 10L, null, 60,
                com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REJECT, null, 8, 0);
        when(dataBudgetStatusService.statusFor(datasourceId, userId)).thenReturn(
                new com.bablsoft.accessflow.core.api.DataBudgetStatus(datasourceId, "ds",
                        List.of(budget)));
        List<List<Object>> rows = List.of(List.of("a"), List.of("b"));
        when(queryExecutor.sampleTable(any())).thenReturn(new SelectExecutionResult(List.of(), rows,
                2, true, Duration.ZERO, java.util.Set.of(), java.util.Set.of(),
                SelectExecutionResult.TRUNCATED_ROW_LIMIT));

        var out = service.sample(datasourceId, organizationId, userId, true, "public", "users", 50);

        assertThat(out.truncatedReason()).isEqualTo(SelectExecutionResult.TRUNCATED_DATA_BUDGET);
    }

    @Test
    void aPreviewCutByItsOwnLimitKeepsTheRowLimitReason() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());
        var budget = new com.bablsoft.accessflow.core.api.DataBudgetConsumption(UUID.randomUUID(),
                "Daily", 1_000L, null, 60,
                com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REJECT, null, 0, 0);
        when(dataBudgetStatusService.statusFor(datasourceId, userId)).thenReturn(
                new com.bablsoft.accessflow.core.api.DataBudgetStatus(datasourceId, "ds",
                        List.of(budget)));
        List<List<Object>> rows = List.of(List.of("a"), List.of("b"));
        when(queryExecutor.sampleTable(any())).thenReturn(new SelectExecutionResult(List.of(), rows,
                2, true, Duration.ZERO, java.util.Set.of(), java.util.Set.of(),
                SelectExecutionResult.TRUNCATED_ROW_LIMIT));

        var out = service.sample(datasourceId, organizationId, userId, true, "public", "users", 2);

        assertThat(out.truncatedReason()).isEqualTo(SelectExecutionResult.TRUNCATED_ROW_LIMIT);
    }

    @Test
    void aFailedUsageWriteNeverFailsThePreview() {
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());
        var budget = new com.bablsoft.accessflow.core.api.DataBudgetConsumption(UUID.randomUUID(),
                "Daily", null, 1_000_000L, 60,
                com.bablsoft.accessflow.core.api.DataBudgetBreachAction.REJECT, null, 0, 0);
        when(dataBudgetStatusService.statusFor(datasourceId, userId)).thenReturn(
                new com.bablsoft.accessflow.core.api.DataBudgetStatus(datasourceId, "ds",
                        List.of(budget)));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(dataBudgetUsageService).record(any());

        var out = service.sample(datasourceId, organizationId, userId, true, "public", "users", 50);

        assertThat(out.rowCount()).isZero();
        var captor = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(captor.capture());
        assertThat(captor.getValue().maxRowsOverride()).isEqualTo(50);
    }
}
