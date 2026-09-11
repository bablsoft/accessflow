package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.PrivilegedAccessQuery;
import com.bablsoft.accessflow.access.api.PrivilegedAccessRow;
import com.bablsoft.accessflow.access.api.StandingBypassKind;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QuerySubmitterEvidence;
import com.bablsoft.accessflow.core.api.QuerySubmitterEvidenceLookupService;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The acceptance criteria of #968 as tests: a QUERY_ADMIN holder with zero permission rows
 * appears labelled as a bypass, a break-glass holder appears with datasources and expiry, a user
 * whose only access is an ordinary grant does not appear, and a custom role carrying QUERY_ADMIN
 * resolves like the system ADMIN role.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultPrivilegedAccessServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ADMIN_ROLE = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final Instant EXPIRY = Instant.parse("2026-10-01T00:00:00Z");

    @Mock RolePermissionHolderLookupService holderLookup;
    @Mock DatasourceUserPermissionLookupService permissionLookup;
    @Mock UserQueryService userQueryService;
    @Mock DatasourceLookupService datasourceLookup;
    @Mock QuerySubmitterEvidenceLookupService evidenceLookup;
    @InjectMocks DefaultPrivilegedAccessService service;

    private final UUID payments = UUID.randomUUID();
    private final UUID analytics = UUID.randomUUID();
    private final UUID retired = UUID.randomUUID();

    @BeforeEach
    void defaults() {
        when(holderLookup.findUserIdsWithPermission(ORG, Permission.QUERY_ADMIN)).thenReturn(List.of());
        when(permissionLookup.findBreakGlassContributionsForOrganization(ORG)).thenReturn(List.of());
        when(datasourceLookup.findActiveRefsByOrganization(ORG)).thenReturn(List.of(
                new DatasourceRef(payments, "payments-prod"), new DatasourceRef(analytics, "analytics")));
        when(evidenceLookup.findBySubmitters(eq(ORG), anyCollection())).thenReturn(Map.of());
        when(userQueryService.findByIds(anyCollection())).thenReturn(List.of());
    }

    @Test
    void aQueryAdminHolderWithZeroPermissionRowsAppearsAsABypassNotAGrant() {
        var root = systemUser("root@example.com", UserRoleType.ADMIN);
        givenQueryAdmins(root.id());
        givenUsers(root);

        var page = service.report(ORG, PrivilegedAccessQuery.empty(), PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(1);
        var row = page.content().get(0);
        assertThat(row.bypassKinds()).containsExactly(StandingBypassKind.QUERY_ADMIN);
        assertThat(row.queryAdmin()).isNotNull();
        assertThat(row.queryAdmin().roleId()).isEqualTo(ADMIN_ROLE);
        assertThat(row.queryAdmin().roleName()).isEqualTo("ADMIN");
        assertThat(row.queryAdmin().systemRole()).isTrue();
        assertThat(row.systemRole()).isTrue();
        assertThat(row.breakGlassGrants()).isEmpty();
        assertThat(row.evidence().submittedQueryCount()).isZero();
        assertThat(row.evidence().lastSubmittedAt()).isNull();
    }

    @Test
    void aCustomRoleCarryingQueryAdminResolvesLikeTheSystemAdminRole() {
        var customRole = UUID.randomUUID();
        var steward = new UserView(UUID.randomUUID(), "dana@example.com", "Dana", null, customRole,
                "Data steward", ORG, true, AuthProviderType.LOCAL, null, null, "en", false, false,
                Instant.now(), null, Instant.now());
        givenQueryAdmins(steward.id());
        givenUsers(steward);

        var row = single(service.report(ORG, PrivilegedAccessQuery.empty(), null));

        assertThat(row.bypassKinds()).containsExactly(StandingBypassKind.QUERY_ADMIN);
        assertThat(row.systemRole()).isFalse();
        assertThat(row.roleName()).isEqualTo("Data steward");
        assertThat(row.queryAdmin().roleId()).isEqualTo(customRole);
        assertThat(row.queryAdmin().systemRole()).isFalse();
    }

    @Test
    void aBreakGlassHolderAppearsWithEachDatasourceAndItsExpiry() {
        var oncall = systemUser("oncall@example.com", UserRoleType.ANALYST);
        var groupId = UUID.randomUUID();
        var directRow = UUID.randomUUID();
        var groupRow = UUID.randomUUID();
        givenContributions(
                group(oncall.id(), analytics, groupRow, groupId, "payments-oncall", EXPIRY),
                direct(oncall.id(), payments, directRow, null));
        givenUsers(oncall);

        var row = single(service.report(ORG, PrivilegedAccessQuery.empty(), null));

        assertThat(row.bypassKinds()).containsExactly(StandingBypassKind.BREAK_GLASS);
        assertThat(row.queryAdmin()).isNull();
        assertThat(row.breakGlassGrants()).hasSize(2);
        // Sorted by datasource name: analytics (via group) before payments-prod (direct).
        var viaGroup = row.breakGlassGrants().get(0);
        assertThat(viaGroup.datasourceName()).isEqualTo("analytics");
        assertThat(viaGroup.sourceKind()).isEqualTo(DatasourcePermissionSourceKind.GROUP);
        assertThat(viaGroup.sourceId()).isEqualTo(groupRow);
        assertThat(viaGroup.groupId()).isEqualTo(groupId);
        assertThat(viaGroup.groupName()).isEqualTo("payments-oncall");
        assertThat(viaGroup.expiresAt()).isEqualTo(EXPIRY);
        var directGrant = row.breakGlassGrants().get(1);
        assertThat(directGrant.datasourceId()).isEqualTo(payments);
        assertThat(directGrant.datasourceName()).isEqualTo("payments-prod");
        assertThat(directGrant.sourceKind()).isEqualTo(DatasourcePermissionSourceKind.DIRECT);
        assertThat(directGrant.sourceId()).isEqualTo(directRow);
        assertThat(directGrant.groupId()).isNull();
        assertThat(directGrant.expiresAt()).isNull();
    }

    @Test
    void directComesBeforeGroupOnTheSameDatasource() {
        var user = systemUser("both@example.com", UserRoleType.ANALYST);
        givenContributions(
                group(user.id(), payments, UUID.randomUUID(), UUID.randomUUID(), "g", null),
                direct(user.id(), payments, UUID.randomUUID(), null));
        givenUsers(user);

        var row = single(service.report(ORG, PrivilegedAccessQuery.empty(), null));

        assertThat(row.breakGlassGrants()).extracting("sourceKind").containsExactly(
                DatasourcePermissionSourceKind.DIRECT, DatasourcePermissionSourceKind.GROUP);
    }

    @Test
    void aUserWhoseOnlyAccessIsAnOrdinaryGrantIsNeverACandidate() {
        // Ordinary grants never reach this service: the lookup returns break-glass rows only, so a
        // reader with no such row and no QUERY_ADMIN is simply absent from the candidate set.
        var page = service.report(ORG, PrivilegedAccessQuery.empty(), PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        verify(userQueryService, never()).findByIds(any());
        verify(evidenceLookup, never()).findBySubmitters(any(), any());
    }

    @Test
    void bothKindsOnOneIdentityYieldOneRow() {
        var root = systemUser("root@example.com", UserRoleType.ADMIN);
        givenQueryAdmins(root.id());
        givenContributions(direct(root.id(), payments, UUID.randomUUID(), null));
        givenUsers(root);

        var page = service.report(ORG, PrivilegedAccessQuery.empty(), null);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).bypassKinds())
                .containsExactly(StandingBypassKind.QUERY_ADMIN, StandingBypassKind.BREAK_GLASS);
    }

    @Test
    void aGrantOnAnInactiveDatasourceIsOmittedAndAloneDoesNotMakeARow() {
        var user = systemUser("stale@example.com", UserRoleType.ANALYST);
        givenContributions(direct(user.id(), retired, UUID.randomUUID(), null));
        givenUsers(user);

        var page = service.report(ORG, PrivilegedAccessQuery.empty(), null);

        assertThat(page.content()).isEmpty();
    }

    @Test
    void inactiveAndForeignUsersAreFilteredOut() {
        var root = systemUser("root@example.com", UserRoleType.ADMIN);
        var disabled = new UserView(UUID.randomUUID(), "z@example.com", "Z", UserRoleType.ANALYST,
                null, "ANALYST", ORG, false, AuthProviderType.LOCAL, null, null, "en", false, false,
                Instant.now(), null, Instant.now());
        var foreign = new UserView(UUID.randomUUID(), "x@example.com", "X", UserRoleType.ANALYST,
                null, "ANALYST", UUID.randomUUID(), true, AuthProviderType.LOCAL, null, null, "en",
                false, false, Instant.now(), null, Instant.now());
        givenQueryAdmins(root.id());
        givenContributions(direct(disabled.id(), payments, UUID.randomUUID(), null),
                direct(foreign.id(), payments, UUID.randomUUID(), null));
        givenUsers(root, disabled, foreign);

        var page = service.report(ORG, PrivilegedAccessQuery.empty(), null);

        assertThat(page.content()).extracting(PrivilegedAccessRow::userId).containsExactly(root.id());
    }

    @Test
    void evidenceIsAttachedPerUserAndDefaultsToNone() {
        var root = systemUser("root@example.com", UserRoleType.ADMIN);
        var quiet = systemUser("quiet@example.com", UserRoleType.ADMIN);
        givenQueryAdmins(root.id(), quiet.id());
        givenUsers(root, quiet);
        var last = Instant.parse("2026-09-10T14:02:11Z");
        var lastBg = Instant.parse("2026-08-30T03:14:00Z");
        when(evidenceLookup.findBySubmitters(eq(ORG), anyCollection())).thenReturn(Map.of(
                root.id(), new QuerySubmitterEvidence(root.id(), 143L, last, 2L, lastBg)));

        var page = service.report(ORG, PrivilegedAccessQuery.empty(), null);

        var byEmail = page.content().stream()
                .collect(Collectors.toMap(PrivilegedAccessRow::email, r -> r));
        assertThat(byEmail.get("root@example.com").evidence().submittedQueryCount()).isEqualTo(143L);
        assertThat(byEmail.get("root@example.com").evidence().lastSubmittedAt()).isEqualTo(last);
        assertThat(byEmail.get("root@example.com").evidence().breakGlassExecutionCount()).isEqualTo(2L);
        assertThat(byEmail.get("root@example.com").evidence().lastBreakGlassAt()).isEqualTo(lastBg);
        assertThat(byEmail.get("quiet@example.com").evidence().submittedQueryCount()).isZero();
        assertThat(byEmail.get("quiet@example.com").evidence().lastBreakGlassAt()).isNull();
    }

    @Test
    void kindFilterSelectsRowsWithoutTrimmingTheirKinds() {
        var root = systemUser("root@example.com", UserRoleType.ADMIN);
        var oncall = systemUser("oncall@example.com", UserRoleType.ANALYST);
        givenQueryAdmins(root.id());
        givenContributions(direct(root.id(), payments, UUID.randomUUID(), null),
                direct(oncall.id(), payments, UUID.randomUUID(), null));
        givenUsers(root, oncall);

        var breakGlassOnly = service.report(ORG,
                new PrivilegedAccessQuery(StandingBypassKind.BREAK_GLASS, null), null);
        var queryAdminOnly = service.report(ORG,
                new PrivilegedAccessQuery(StandingBypassKind.QUERY_ADMIN, null), null);

        assertThat(breakGlassOnly.content()).extracting(PrivilegedAccessRow::email)
                .containsExactly("oncall@example.com", "root@example.com");
        assertThat(queryAdminOnly.content()).extracting(PrivilegedAccessRow::email)
                .containsExactly("root@example.com");
        assertThat(queryAdminOnly.content().get(0).bypassKinds())
                .containsExactly(StandingBypassKind.QUERY_ADMIN, StandingBypassKind.BREAK_GLASS);
    }

    @Test
    void userIdFilterNarrowsBeforeTheUserFetchAndAnUnknownIdIsAnEmptyPage() {
        var root = systemUser("root@example.com", UserRoleType.ADMIN);
        var other = systemUser("other@example.com", UserRoleType.ADMIN);
        givenQueryAdmins(root.id(), other.id());
        givenUsers(root, other);

        var narrowed = service.report(ORG, new PrivilegedAccessQuery(null, root.id()),
                PageRequest.of(0, 20));
        var unknown = service.report(ORG, new PrivilegedAccessQuery(null, UUID.randomUUID()),
                PageRequest.of(0, 20));

        assertThat(narrowed.content()).extracting(PrivilegedAccessRow::userId).containsExactly(root.id());
        assertThat(unknown.content()).isEmpty();
        assertThat(unknown.totalElements()).isZero();
        verify(userQueryService).findByIds(Set.of(root.id()));
    }

    @Test
    void rowsAreSortedByEmailAndSlicedWithTrueTotals() {
        var c = systemUser("carol@example.com", UserRoleType.ADMIN);
        var a = systemUser("Alice@example.com", UserRoleType.ADMIN);
        var b = systemUser("bob@example.com", UserRoleType.ADMIN);
        givenQueryAdmins(c.id(), a.id(), b.id());
        givenUsers(c, a, b);

        var first = service.report(ORG, PrivilegedAccessQuery.empty(), PageRequest.of(0, 2));
        var second = service.report(ORG, PrivilegedAccessQuery.empty(), PageRequest.of(1, 2));
        var beyond = service.report(ORG, PrivilegedAccessQuery.empty(), PageRequest.of(5, 2));
        var unpaged = service.report(ORG, PrivilegedAccessQuery.empty(), null);

        assertThat(first.content()).extracting(PrivilegedAccessRow::email)
                .containsExactly("Alice@example.com", "bob@example.com");
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.content()).extracting(PrivilegedAccessRow::email)
                .containsExactly("carol@example.com");
        assertThat(beyond.content()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(3);
        assertThat(unpaged.content()).hasSize(3);
        assertThat(unpaged.size()).isEqualTo(3);
        assertThat(unpaged.totalPages()).isEqualTo(1);
    }

    @Test
    void aNullQueryMeansNoFilter() {
        var root = systemUser("root@example.com", UserRoleType.ADMIN);
        givenQueryAdmins(root.id());
        givenUsers(root);

        assertThat(service.report(ORG, null, null).totalElements()).isEqualTo(1);
    }

    private static PrivilegedAccessRow single(com.bablsoft.accessflow.core.api.PageResponse<PrivilegedAccessRow> page) {
        assertThat(page.content()).hasSize(1);
        return page.content().get(0);
    }

    private void givenQueryAdmins(UUID... ids) {
        when(holderLookup.findUserIdsWithPermission(ORG, Permission.QUERY_ADMIN))
                .thenReturn(List.of(ids));
    }

    private void givenContributions(DatasourcePermissionContribution... contributions) {
        when(permissionLookup.findBreakGlassContributionsForOrganization(ORG))
                .thenReturn(List.of(contributions));
    }

    private void givenUsers(UserView... users) {
        var byId = Arrays.stream(users).collect(Collectors.toMap(UserView::id, u -> u));
        when(userQueryService.findByIds(anyCollection())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return ids.stream().map(byId::get).filter(u -> u != null).toList();
        });
    }

    private static UserView systemUser(String email, UserRoleType role) {
        var roleId = role == UserRoleType.ADMIN ? ADMIN_ROLE : UUID.randomUUID();
        return new UserView(UUID.randomUUID(), email, "User", role, roleId, role.name(), ORG, true,
                AuthProviderType.LOCAL, null, null, "en", false, false, Instant.now(), null,
                Instant.now());
    }

    private static DatasourcePermissionContribution direct(UUID userId, UUID datasourceId,
                                                           UUID rowId, Instant expiresAt) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.DIRECT, rowId,
                userId, datasourceId, null, null, true, false, false, true, List.of(), List.of(),
                List.of(), expiresAt, null);
    }

    private static DatasourcePermissionContribution group(UUID userId, UUID datasourceId,
                                                          UUID rowId, UUID groupId, String groupName,
                                                          Instant expiresAt) {
        return new DatasourcePermissionContribution(DatasourcePermissionSourceKind.GROUP, rowId,
                userId, datasourceId, groupId, groupName, true, false, false, true, List.of(),
                List.of(), List.of(), expiresAt, null);
    }
}
