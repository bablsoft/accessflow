package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.AccessSourceKind;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessQuery;
import com.bablsoft.accessflow.workflow.api.StatementCapability;
import com.bablsoft.accessflow.workflow.api.TableScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultEffectiveAccessServiceTest {

    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock RolePermissionHolderLookupService rolePermissionHolderLookupService;
    @Mock AccessGrantLookupService accessGrantLookupService;
    @Mock UserQueryService userQueryService;

    private DefaultEffectiveAccessService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID analystId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void buildService() {
        service = new DefaultEffectiveAccessService(datasourceAdminService, permissionLookupService,
                rolePermissionHolderLookupService, accessGrantLookupService, userQueryService);
        // The real merge, not a mock of it: the point of the service is that its verdict matches the
        // one enforcement computes, and mocking the merge away would stop testing that.
        when(permissionLookupService.mergeContributions(any())).thenAnswer(invocation -> {
            List<DatasourcePermissionContribution> parts = invocation.getArgument(0);
            return parts.isEmpty() ? Optional.empty() : Optional.of(merge(parts));
        });
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of());
        when(accessGrantLookupService.findPreApprovingGrantsForDatasource(organizationId,
                datasourceId)).thenReturn(List.of());
    }

    @Test
    void aDirectGrantThatCoversTheTableIsReported() {
        givenContributions(direct(analystId, true, false, List.of(), List.of("public.payments"),
                null, false));
        givenUsers(user(analystId, "dana@example.com"));

        var page = report(StatementCapability.READ, "public.payments");

        assertThat(page.content()).hasSize(1);
        var row = page.content().get(0);
        assertThat(row.granted()).isTrue();
        assertThat(row.tableScope()).isEqualTo(TableScope.ALLOW_LISTED);
        assertThat(row.sources()).singleElement().satisfies(source -> {
            assertThat(source.kind()).isEqualTo(AccessSourceKind.DIRECT_PERMISSION);
            assertThat(source.coveringAllowListEntry()).isEqualTo("public.payments");
            assertThat(source.grantsCapability()).isTrue();
        });
    }

    @Test
    void aSchemaEntryCoversTheQualifiedTableAndIsNamedAsSuch() {
        givenContributions(direct(analystId, false, true, List.of("public"), List.of(), null,
                false));
        givenUsers(user(analystId, "dana@example.com"));

        var page = report(StatementCapability.WRITE, "public.payments");

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).sources().get(0).coveringAllowListEntry())
                .isEqualTo("public");
    }

    @Test
    void anUnrestrictedGrantIsAllTablesAndIsNeverEnumerated() {
        givenContributions(direct(analystId, true, false, List.of(), List.of(), null, false));
        givenUsers(user(analystId, "dana@example.com"));

        var page = report(StatementCapability.READ, "anything.at.all");

        assertThat(page.content().get(0).tableScope()).isEqualTo(TableScope.ALL_TABLES);
        assertThat(page.content().get(0).sources().get(0).coveringAllowListEntry()).isNull();
    }

    @Test
    void aUserWithoutTheCapabilityIsNotListed() {
        givenContributions(direct(analystId, true, false, List.of(), List.of(), null, false));
        givenUsers(user(analystId, "dana@example.com"));

        assertThat(report(StatementCapability.WRITE, "public.payments").content()).isEmpty();
    }

    @Test
    void aTableOutsideTheAllowListIsNotListed() {
        givenContributions(direct(analystId, true, false, List.of(), List.of("orders"), null,
                false));
        givenUsers(user(analystId, "dana@example.com"));

        assertThat(report(StatementCapability.READ, "public.payments").content()).isEmpty();
    }

    @Test
    void twoGrantsThatEachFallShortCanTogetherBeEnough() {
        // The merge ORs booleans and unions allow-lists, so a capability from one grant combines
        // with table coverage from another. Judging each source alone would miss this.
        givenContributions(
                direct(analystId, false, true, List.of(), List.of("orders"), null, false),
                group(analystId, true, false, List.of("public"), List.of(), "analysts"));
        givenUsers(user(analystId, "dana@example.com"));

        var page = report(StatementCapability.WRITE, "public.payments");

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).granted()).isTrue();
        assertThat(page.content().get(0).sources()).extracting("kind")
                .containsExactly(AccessSourceKind.DIRECT_PERMISSION,
                        AccessSourceKind.GROUP_PERMISSION);
    }

    @Test
    void aGroupSourceNamesTheGroupThatGrantedIt() {
        givenContributions(group(analystId, true, false, List.of("public"), List.of(),
                "payments-oncall"));
        givenUsers(user(analystId, "dana@example.com"));

        var source = report(StatementCapability.READ, "public.payments").content().get(0)
                .sources().get(0);

        assertThat(source.kind()).isEqualTo(AccessSourceKind.GROUP_PERMISSION);
        assertThat(source.groupName()).isEqualTo("payments-oncall");
    }

    @Test
    void aQueryAdminHolderWithZeroPermissionRowsAppears() {
        // The whole point of the endpoint: QUERY_ADMIN skips the per-datasource gate, so its holders
        // can write to everything while appearing in no permission table.
        givenContributions();
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of(adminId));
        givenUsers(user(adminId, "root@example.com"));

        var page = report(StatementCapability.WRITE, "public.payments");

        assertThat(page.content()).hasSize(1);
        var row = page.content().get(0);
        assertThat(row.granted()).isTrue();
        assertThat(row.tableScope()).isEqualTo(TableScope.ALL_TABLES);
        assertThat(row.effectiveExpiresAt()).isNull();
        assertThat(row.sources()).singleElement()
                .satisfies(s -> assertThat(s.kind())
                        .isEqualTo(AccessSourceKind.QUERY_ADMIN_BYPASS));
    }

    @Test
    void aRowStampedWithAnActivePreApprovingGrantIsJitAndPreApproves() {
        var expiry = Instant.now().plusSeconds(3600);
        var grantId = UUID.randomUUID();
        givenContributions(jit(analystId, expiry, grantId));
        givenUsers(user(analystId, "dana@example.com"));
        when(accessGrantLookupService.findPreApprovingGrantsForDatasource(organizationId,
                datasourceId)).thenReturn(List.of(grant(grantId, analystId)));

        var source = report(StatementCapability.READ, "public.payments").content().get(0)
                .sources().get(0);

        assertThat(source.kind()).isEqualTo(AccessSourceKind.JIT_GRANT);
        assertThat(source.preApproveQueries()).isTrue();
        assertThat(source.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void aJitRowWithNoActivePreApprovingGrantKeepsTheLabelWithoutPreApproval() {
        // The grant expired (or was never opted in) and the user holds no other — the row is still
        // a JIT row by foreign key; only the pre-approval is gone.
        givenContributions(jit(analystId, Instant.now().plusSeconds(3600), UUID.randomUUID()));
        givenUsers(user(analystId, "dana@example.com"));

        var source = report(StatementCapability.READ, "public.payments").content().get(0)
                .sources().get(0);

        assertThat(source.kind()).isEqualTo(AccessSourceKind.JIT_GRANT);
        assertThat(source.preApproveQueries()).isFalse();
    }

    @Test
    void preApprovalFollowsTheUserNotTheRowsOwnGrant() {
        // Grant stacking: a later non-pre-approving grant B replaced the row grant A materialised,
        // but A is still APPROVED and unexpired — the submission fast-path keys on the user, so
        // queries still skip review and the flag must say so.
        givenContributions(jit(analystId, Instant.now().plusSeconds(3600), UUID.randomUUID()));
        givenUsers(user(analystId, "dana@example.com"));
        when(accessGrantLookupService.findPreApprovingGrantsForDatasource(organizationId,
                datasourceId)).thenReturn(List.of(grant(UUID.randomUUID(), analystId)));

        var source = report(StatementCapability.READ, "public.payments").content().get(0)
                .sources().get(0);

        assertThat(source.kind()).isEqualTo(AccessSourceKind.JIT_GRANT);
        assertThat(source.preApproveQueries()).isTrue();
    }

    @Test
    void aStandingRowNextToAnUnrelatedActiveGrantIsNotMislabelled() {
        // The pre-#969 correlation on (user, datasource) would have called this row JIT_GRANT.
        givenContributions(direct(analystId, true, false, List.of("public"), List.of(),
                Instant.now().plusSeconds(3600), false));
        givenUsers(user(analystId, "dana@example.com"));
        when(accessGrantLookupService.findPreApprovingGrantsForDatasource(organizationId,
                datasourceId)).thenReturn(List.of(grant(UUID.randomUUID(), analystId)));

        var source = report(StatementCapability.READ, "public.payments").content().get(0)
                .sources().get(0);

        assertThat(source.kind()).isEqualTo(AccessSourceKind.DIRECT_PERMISSION);
        assertThat(source.preApproveQueries()).isFalse();
    }

    @Test
    void aTimeBoxedRowWithNoOriginatingRequestIsADirectPermission() {
        givenContributions(direct(analystId, true, false, List.of("public"), List.of(),
                Instant.now().plusSeconds(3600), false));
        givenUsers(user(analystId, "dana@example.com"));

        var source = report(StatementCapability.READ, "public.payments").content().get(0)
                .sources().get(0);

        assertThat(source.kind()).isEqualTo(AccessSourceKind.DIRECT_PERMISSION);
        assertThat(source.preApproveQueries()).isFalse();
    }

    @Test
    void breakGlassIsReportedAsItsOwnSourceAndDoesNotGrantOrdinaryAccess() {
        givenContributions(direct(analystId, false, false, List.of("public"), List.of(), null,
                true));
        givenUsers(user(analystId, "dana@example.com"));

        var page = report(StatementCapability.READ, "public.payments");

        // No ordinary capability, so the user is not listed at all — break-glass is a separate
        // submission mode, not a route to a plain SELECT.
        assertThat(page.content()).isEmpty();
    }

    @Test
    void aBreakGlassHolderWhoAlsoHasOrdinaryAccessCarriesBothSources() {
        givenContributions(direct(analystId, true, false, List.of("public"), List.of(), null, true));
        givenUsers(user(analystId, "dana@example.com"));

        var row = report(StatementCapability.READ, "public.payments").content().get(0);

        assertThat(row.canBreakGlass()).isTrue();
        assertThat(row.sources()).extracting("kind")
                .containsExactly(AccessSourceKind.DIRECT_PERMISSION, AccessSourceKind.BREAK_GLASS);
    }

    @Test
    void aQuotedMixedCaseTableResolvesTheSameAsTheEnforcementGateWould() {
        givenContributions(direct(analystId, true, false, List.of(), List.of("\"Public\".Payments"),
                null, false));
        givenUsers(user(analystId, "dana@example.com"));

        var page = report(StatementCapability.READ, "  \"Public\".[Payments]  ");

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).sources().get(0).coveringAllowListEntry())
                .isEqualTo("public.payments");
    }

    @Test
    void inactiveUsersAndOtherOrganizationsAreExcluded() {
        var otherOrgUser = UUID.randomUUID();
        var inactive = UUID.randomUUID();
        givenContributions(direct(analystId, true, false, List.of(), List.of(), null, false),
                direct(otherOrgUser, true, false, List.of(), List.of(), null, false),
                direct(inactive, true, false, List.of(), List.of(), null, false));
        var foreign = new UserView(otherOrgUser, "x@y.io", "X", UserRoleType.ANALYST, null,
                "ANALYST", UUID.randomUUID(), true, AuthProviderType.LOCAL, null, null, "en", false,
                false, Instant.now(), null, Instant.now());
        var disabled = new UserView(inactive, "z@y.io", "Z", UserRoleType.ANALYST, null, "ANALYST",
                organizationId, false, AuthProviderType.LOCAL, null, null, "en", false, false,
                Instant.now(), null, Instant.now());
        givenUsers(user(analystId, "dana@example.com"), foreign, disabled);

        var page = report(StatementCapability.READ, "public.payments");

        assertThat(page.content()).extracting("userId").containsExactly(analystId);
    }

    @Test
    void rowsAreSortedByEmailAndSlicedWithTrueTotals() {
        var second = UUID.randomUUID();
        givenContributions(direct(analystId, true, false, List.of(), List.of(), null, false),
                direct(second, true, false, List.of(), List.of(), null, false));
        givenUsers(user(analystId, "zoe@example.com"), user(second, "amir@example.com"));

        var firstPage = service.report(organizationId,
                new EffectiveAccessQuery(datasourceId, "public.payments", StatementCapability.READ),
                PageRequest.of(0, 1));

        assertThat(firstPage.content()).extracting("email").containsExactly("amir@example.com");
        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(firstPage.totalPages()).isEqualTo(2);

        var secondPage = service.report(organizationId,
                new EffectiveAccessQuery(datasourceId, "public.payments", StatementCapability.READ),
                PageRequest.of(1, 1));

        assertThat(secondPage.content()).extracting("email").containsExactly("zoe@example.com");
    }

    @Test
    void aPageBeyondTheEndIsEmptyRatherThanAnError() {
        givenContributions(direct(analystId, true, false, List.of(), List.of(), null, false));
        givenUsers(user(analystId, "dana@example.com"));

        var page = service.report(organizationId,
                new EffectiveAccessQuery(datasourceId, "public.payments", StatementCapability.READ),
                PageRequest.of(9, 20));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void ddlIsAnswersFromCanDdl() {
        givenContributions(direct(analystId, true, true, List.of(), List.of(), null, false));
        givenUsers(user(analystId, "dana@example.com"));
        assertThat(report(StatementCapability.DDL, "public.payments").content()).isEmpty();

        givenContributions(ddlGrant(analystId));
        assertThat(report(StatementCapability.DDL, "public.payments").content()).hasSize(1);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private com.bablsoft.accessflow.core.api.PageResponse<
            com.bablsoft.accessflow.workflow.api.EffectiveAccessRow> report(
            StatementCapability capability, String table) {
        return service.report(organizationId,
                new EffectiveAccessQuery(datasourceId, table, capability), PageRequest.of(0, 20));
    }

    private void givenContributions(DatasourcePermissionContribution... contributions) {
        when(permissionLookupService.findContributionsForDatasource(datasourceId))
                .thenReturn(List.of(contributions));
    }

    private void givenUsers(UserView... users) {
        when(userQueryService.findByIds(any())).thenReturn(List.of(users));
    }

    private DatasourcePermissionContribution direct(UUID userId, boolean canRead, boolean canWrite,
                                                    List<String> schemas, List<String> tables,
                                                    Instant expiresAt, boolean breakGlass) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.DIRECT,
                UUID.randomUUID(), userId, datasourceId, null, null, canRead, canWrite, false,
                breakGlass, schemas, tables, List.of(), expiresAt, null);
    }

    /** A time-boxed direct row materialised from the given JIT request (#969). */
    private DatasourcePermissionContribution jit(UUID userId, Instant expiresAt,
                                                 UUID accessGrantRequestId) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.DIRECT,
                UUID.randomUUID(), userId, datasourceId, null, null, true, false, false, false,
                List.of("public"), List.of(), List.of(), expiresAt, accessGrantRequestId);
    }

    private DatasourcePermissionContribution ddlGrant(UUID userId) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.DIRECT,
                UUID.randomUUID(), userId, datasourceId, null, null, false, false, true, false,
                List.of(), List.of(), List.of(), null, null);
    }

    private DatasourcePermissionContribution group(UUID userId, boolean canRead, boolean canWrite,
                                                   List<String> schemas, List<String> tables,
                                                   String groupName) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.GROUP,
                UUID.randomUUID(), userId, datasourceId, UUID.randomUUID(), groupName, canRead,
                canWrite, false, false, schemas, tables, List.of(), null, null);
    }

    private AccessGrantView grant(UUID grantId, UUID requesterId) {
        return new AccessGrantView(grantId, organizationId, requesterId, datasourceId,
                true, false, false, List.of(), List.of(), AccessGrantStatus.APPROVED,
                Instant.now().plusSeconds(3600), UUID.randomUUID(), "approver@x.io", Instant.now());
    }

    private UserView user(UUID id, String email) {
        return new UserView(id, email, "User", UserRoleType.ANALYST, null, "ANALYST",
                organizationId, true, AuthProviderType.LOCAL, null, null, "en", false, false,
                Instant.now(), null, Instant.now());
    }

    /** Mirrors core's merge: booleans OR, allow-lists union with unrestricted winning. */
    private DatasourceUserPermissionView merge(List<DatasourcePermissionContribution> parts) {
        boolean canRead = false;
        boolean canWrite = false;
        boolean canDdl = false;
        boolean breakGlass = false;
        Instant expiresAt = parts.get(0).expiresAt();
        boolean anyNeverExpires = false;
        var schemas = new LinkedHashSet<String>();
        var tables = new LinkedHashSet<String>();
        boolean unrestrictedSchemas = false;
        boolean unrestrictedTables = false;
        for (var part : parts) {
            canRead |= part.canRead();
            canWrite |= part.canWrite();
            canDdl |= part.canDdl();
            breakGlass |= part.canBreakGlass();
            unrestrictedSchemas |= part.allowedSchemas().isEmpty();
            unrestrictedTables |= part.allowedTables().isEmpty();
            schemas.addAll(part.allowedSchemas());
            tables.addAll(part.allowedTables());
            if (part.expiresAt() == null) {
                anyNeverExpires = true;
            } else if (expiresAt != null && part.expiresAt().isAfter(expiresAt)) {
                expiresAt = part.expiresAt();
            }
        }
        return new DatasourceUserPermissionView(parts.get(0).sourceId(), parts.get(0).userId(),
                datasourceId, canRead, canWrite, canDdl, breakGlass,
                unrestrictedSchemas ? List.of() : new ArrayList<>(schemas),
                unrestrictedTables ? List.of() : new ArrayList<>(tables), List.of(),
                anyNeverExpires ? null : expiresAt);
    }
}
