package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.AcceptedApiSchemaRevision;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftFindingView;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftStatus;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRefreshResult;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionView;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import com.bablsoft.accessflow.apigov.api.OperationFilterPreview;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiSchemaControllerTest {

    private ApiSchemaService schemaService;
    private ApiSchemaRevisionService revisionService;
    private ApiConnectorAdminService connectorService;
    private AuditLogService auditLogService;
    private ApiSchemaController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.9", "ua/1");

    @BeforeEach
    void setUp() {
        schemaService = mock(ApiSchemaService.class);
        connectorService = mock(ApiConnectorAdminService.class);
        auditLogService = mock(AuditLogService.class);
        revisionService = mock(ApiSchemaRevisionService.class);
        controller = new ApiSchemaController(schemaService, revisionService, connectorService,
                new ApiGovAuditWriter(auditLogService));
    }

    private Authentication auth(UserRoleType role) {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(JwtClaims.forSystemRole(userId, "a@acme.test", role, orgId));
        return authentication;
    }

    private final UUID revisionId = UUID.randomUUID();

    private ApiSchemaView schemaView() {
        return new ApiSchemaView(UUID.randomUUID(), connectorId, ApiSchemaType.OPENAPI, null, 3, 5,
                OperationFilter.EMPTY, null, 60, revisionId, Instant.now(), "fetch failed", Instant.now());
    }

    @Test
    void uploadReturnsResponseAndAudits() {
        when(schemaService.upload(eq(connectorId), eq(orgId), eq(ApiSchemaType.OPENAPI), eq("spec"), any(), any(), eq(30)))
                .thenReturn(schemaView());

        var response = controller.upload(connectorId,
                new UploadApiSchemaRequest(ApiSchemaType.OPENAPI, "spec", "https://example.test/s", null, 30),
                auth(UserRoleType.ADMIN),
                auditContext);

        assertThat(response.operationCount()).isEqualTo(3);
        assertThat(response.totalOperationCount()).isEqualTo(5);
        verify(auditLogService).record(auditEntry(AuditAction.API_SCHEMA_UPLOADED));
    }

    @Test
    void previewReturnsCountsWithoutAuditing() {
        when(schemaService.previewFilter(eq(connectorId), eq(orgId), eq(ApiSchemaType.OPENAPI), eq("spec"),
                any(), any()))
                .thenReturn(new OperationFilterPreview(2, 1, List.of(
                        new ApiOperation("internalSync", "POST", "/internal/sync", null, true, null, null))));

        var response = controller.preview(connectorId,
                new UploadApiSchemaRequest(ApiSchemaType.OPENAPI, "spec", null, null, null), auth(UserRoleType.ADMIN));

        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.keptCount()).isEqualTo(1);
        assertThat(response.excluded()).hasSize(1);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateFilterAuditsAndReturnsResponse() {
        var schemaId = UUID.randomUUID();
        when(schemaService.updateFilter(eq(connectorId), eq(orgId), eq(schemaId), any()))
                .thenReturn(schemaView());

        var response = controller.updateFilter(connectorId, schemaId,
                new OperationFilterRequest(null, List.of("/internal/**"), null, null, null, null, null, null, false),
                auth(UserRoleType.ADMIN), auditContext);

        assertThat(response.operationCount()).isEqualTo(3);
        verify(auditLogService).record(auditEntry(AuditAction.API_SCHEMA_UPLOADED));
    }

    @Test
    void listMapsViews() {
        when(schemaService.list(connectorId, orgId)).thenReturn(List.of(schemaView()));

        var listed = controller.list(connectorId, auth(UserRoleType.ADMIN));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).refreshIntervalMinutes()).isEqualTo(60);
        assertThat(listed.get(0).currentRevisionId()).isEqualTo(revisionId);
        assertThat(listed.get(0).lastRefreshAt()).isNotNull();
        assertThat(listed.get(0).lastRefreshError()).isEqualTo("fetch failed");
    }

    @Test
    void deleteAudits() {
        controller.delete(connectorId, UUID.randomUUID(), auth(UserRoleType.ADMIN), auditContext);

        verify(auditLogService).record(auditEntry(AuditAction.API_SCHEMA_DELETED));
    }

    @Test
    void operationsForAdminSkipsPermissionCheck() {
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of(
                new ApiOperation("listPets", "GET", "/pets", null, false, null, null)));

        var ops = controller.operations(connectorId, auth(UserRoleType.ADMIN));

        assertThat(ops).hasSize(1);
        verifyNoInteractions(connectorService);
    }

    @Test
    void operationsForNonAdminEnforcesPermission() {
        when(schemaService.listOperations(connectorId, orgId)).thenReturn(List.of());

        controller.operations(connectorId, auth(UserRoleType.ANALYST));

        verify(connectorService).getForUser(connectorId, orgId, userId);
    }

    @Test
    void uploadRequestNeedsContentOrSourceUrl() {
        assertThat(new UploadApiSchemaRequest(ApiSchemaType.OPENAPI, "", "https://x.test/s", null, null)
                .isContentOrSourceUrlPresent()).isTrue();
        assertThat(new UploadApiSchemaRequest(ApiSchemaType.OPENAPI, "spec", null, null, null)
                .isContentOrSourceUrlPresent()).isTrue();
        assertThat(new UploadApiSchemaRequest(ApiSchemaType.OPENAPI, " ", " ", null, null)
                .isContentOrSourceUrlPresent()).isFalse();
        assertThat(new UploadApiSchemaRequest(ApiSchemaType.OPENAPI, null, null, null, null)
                .isContentOrSourceUrlPresent()).isFalse();
    }

    @Test
    void refreshMapsTheResultWithoutAuditing() {
        var schemaId = UUID.randomUUID();
        when(revisionService.refresh(connectorId, orgId, schemaId))
                .thenReturn(new ApiSchemaRefreshResult(revisionId, 2, false));

        var response = controller.refresh(connectorId, schemaId, auth(UserRoleType.ADMIN));

        assertThat(response.revisionId()).isEqualTo(revisionId);
        assertThat(response.findingCount()).isEqualTo(2);
        assertThat(response.unchanged()).isFalse();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateRefreshPolicyAuditsAndReturnsTheSchema() {
        var schemaId = UUID.randomUUID();
        when(revisionService.updateRefreshPolicy(connectorId, orgId, schemaId, 60)).thenReturn(schemaView());

        var response = controller.updateRefreshPolicy(connectorId, schemaId,
                new UpdateApiSchemaRefreshPolicyRequest(60), auth(UserRoleType.ADMIN), auditContext);

        assertThat(response.refreshIntervalMinutes()).isEqualTo(60);
        verify(auditLogService).record(org.mockito.ArgumentMatchers.argThat(e -> e != null
                && e.action() == AuditAction.API_SCHEMA_UPLOADED
                && "refresh_policy_updated".equals(e.metadata().get("action"))));
    }

    @Test
    void revisionsMapsViews() {
        var schemaId = UUID.randomUUID();
        when(revisionService.listRevisions(connectorId, orgId, schemaId)).thenReturn(List.of(revisionView()));

        var listed = controller.revisions(connectorId, schemaId, auth(UserRoleType.ADMIN));

        assertThat(listed).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(revisionId);
            assertThat(r.contentHash()).isEqualTo("a".repeat(64));
            assertThat(r.operationCount()).isEqualTo(4);
        });
    }

    @Test
    void acceptAuditsTheRevisionAndReturnsTheSchema() {
        var schemaId = UUID.randomUUID();
        when(revisionService.accept(connectorId, orgId, schemaId, revisionId, userId))
                .thenReturn(new AcceptedApiSchemaRevision(schemaView(), revisionView()));

        var response = controller.accept(connectorId, schemaId, revisionId, auth(UserRoleType.ADMIN), auditContext);

        assertThat(response.currentRevisionId()).isEqualTo(revisionId);
        verify(auditLogService).record(org.mockito.ArgumentMatchers.argThat(e -> e != null
                && e.action() == AuditAction.API_SCHEMA_REVISION_ACCEPTED
                && revisionId.equals(e.metadata().get("revision_id"))
                && ("a".repeat(64)).equals(e.metadata().get("content_hash"))
                && Integer.valueOf(3).equals(e.metadata().get("operation_count"))));
    }

    @Test
    void findingsMapViewsAndPassTheStatusFilter() {
        var before = new ApiOperation("createPet", "POST", "/pets", null, true, null, null);
        var grant = UUID.randomUUID();
        when(revisionService.listFindings(connectorId, orgId, ApiSchemaDriftStatus.OPEN)).thenReturn(List.of(
                new ApiSchemaDriftFindingView(UUID.randomUUID(), UUID.randomUUID(), revisionId,
                        ApiSchemaDriftKind.OPERATION_REMOVED, "createPet", before, null, 1, List.of(grant),
                        List.of(), ApiSchemaDriftStatus.OPEN, Instant.now(), null, null)));

        var listed = controller.findings(connectorId, ApiSchemaDriftStatus.OPEN, auth(UserRoleType.ADMIN));

        assertThat(listed).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(ApiSchemaDriftKind.OPERATION_REMOVED);
            assertThat(f.before().operationId()).isEqualTo("createPet");
            assertThat(f.after()).isNull();
            assertThat(f.affectedUserGrantIds()).containsExactly(grant);
        });
    }

    @Test
    void findingsMapTheAfterSide() {
        var after = new ApiOperation("deletePet", "DELETE", "/pets/{id}", null, true, null, null);
        when(revisionService.listFindings(connectorId, orgId, null)).thenReturn(List.of(
                new ApiSchemaDriftFindingView(UUID.randomUUID(), UUID.randomUUID(), revisionId,
                        ApiSchemaDriftKind.OPERATION_ADDED, "deletePet", null, after, 0, List.of(), List.of(),
                        ApiSchemaDriftStatus.OPEN, Instant.now(), null, null)));

        var listed = controller.findings(connectorId, null, auth(UserRoleType.ADMIN));

        assertThat(listed.getFirst().before()).isNull();
        assertThat(listed.getFirst().after().operationId()).isEqualTo("deletePet");
    }

    @Test
    void dismissAuditsOnlyWhenTheFindingChanged() {
        var findingId = UUID.randomUUID();
        when(revisionService.dismissFinding(connectorId, orgId, findingId, userId)).thenReturn(true, false);

        controller.dismiss(connectorId, findingId, auth(UserRoleType.ADMIN), auditContext);
        controller.dismiss(connectorId, findingId, auth(UserRoleType.ADMIN), auditContext);

        verify(auditLogService, org.mockito.Mockito.times(1)).record(auditEntry(AuditAction.API_SCHEMA_DRIFT_DISMISSED));
    }

    private ApiSchemaRevisionView revisionView() {
        return new ApiSchemaRevisionView(revisionId, UUID.randomUUID(), "a".repeat(64), 4, Instant.now(),
                Instant.now(), null, null);
    }

    private static AuditEntry auditEntry(AuditAction action) {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.action() == action);
    }
}
