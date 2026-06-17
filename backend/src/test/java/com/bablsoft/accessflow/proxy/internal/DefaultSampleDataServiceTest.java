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
    private QueryExecutor queryExecutor;

    @InjectMocks
    private DefaultSampleDataService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private final SelectExecutionResult result = new SelectExecutionResult(
            List.of(), List.of(), 0, false, Duration.ZERO);

    @BeforeEach
    void setUp() {
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

    private DatasourceUserPermissionView permission(boolean canRead, List<String> restrictedColumns,
                                                    List<String> allowedSchemas,
                                                    List<String> allowedTables) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId, canRead,
                false, false, allowedSchemas, allowedTables, restrictedColumns, null);
    }
}
