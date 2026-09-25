package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryShape;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceGroupPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDatasourceUserPermissionLookupServiceTest {

    @Mock DatasourceUserPermissionRepository permissionRepository;
    @Mock DatasourceGroupPermissionRepository groupPermissionRepository;
    @Mock UserGroupMembershipRepository membershipRepository;
    @InjectMocks DefaultDatasourceUserPermissionLookupService service;

    @Test
    void findForMapsAllFields() {
        var permId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var expiresAt = Instant.parse("2026-12-31T23:59:59Z");

        var entity = newPermission(permId, userId, datasourceId);
        entity.setCanRead(true);
        entity.setCanWrite(true);
        entity.setCanDdl(false);
        entity.setAllowedSchemas(new String[] {"public", "reporting"});
        entity.setAllowedTables(new String[] {"users", "orders"});
        entity.setRestrictedColumns(new String[] {"public.users.ssn", "public.users.email"});
        entity.setExpiresAt(expiresAt);

        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(entity));

        var result = service.findFor(userId, datasourceId);

        assertThat(result).isPresent();
        var view = result.get();
        assertThat(view.id()).isEqualTo(permId);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.datasourceId()).isEqualTo(datasourceId);
        assertThat(view.canRead()).isTrue();
        assertThat(view.canWrite()).isTrue();
        assertThat(view.canDdl()).isFalse();
        assertThat(view.allowedSchemas()).containsExactly("public", "reporting");
        assertThat(view.allowedTables()).containsExactly("users", "orders");
        assertThat(view.restrictedColumns()).containsExactly("public.users.ssn", "public.users.email");
        assertThat(view.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void findForReturnsEmptyAllowedListsWhenArraysAreNull() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var entity = newPermission(UUID.randomUUID(), userId, datasourceId);
        entity.setAllowedSchemas(null);
        entity.setAllowedTables(null);

        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(entity));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.allowedSchemas()).isEmpty();
        assertThat(view.allowedTables()).isEmpty();
        assertThat(view.restrictedColumns()).isEmpty();
        assertThat(view.expiresAt()).isNull();
    }

    @Test
    void findForReturnsEmptyWhenMissing() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.empty());

        assertThat(service.findFor(userId, datasourceId)).isEmpty();
    }

    @Test
    void findForUnionsFlagsAcrossDirectAndGroupGrants() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanWrite(true);
        group.setCanDdl(true);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.canRead()).isTrue();
        assertThat(view.canWrite()).isTrue();
        assertThat(view.canDdl()).isTrue();
    }

    @Test
    void findForResolvesGroupOnlyGrant() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.empty());
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.canRead()).isTrue();
    }

    @Test
    void findForIgnoresExpiredGroupGrant() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setExpiresAt(Instant.now().minusSeconds(60));
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.empty());
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        assertThat(service.findFor(userId, datasourceId)).isEmpty();
    }

    @Test
    void findForUnionsAllowListsAndIntersectsRestrictions() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setAllowedSchemas(new String[] {"public"});
        direct.setRestrictedColumns(new String[] {"public.users.ssn", "public.users.email"});
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setAllowedSchemas(new String[] {"reporting"});
        group.setRestrictedColumns(new String[] {"public.users.ssn"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.allowedSchemas()).containsExactlyInAnyOrder("public", "reporting");
        // A column is masked only when every contributing grant masks it (least-restrictive).
        assertThat(view.restrictedColumns()).containsExactly("public.users.ssn");
    }

    @Test
    void findForUnionsDeniedColumnsCaseInsensitively() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setDeniedColumns(new String[] {"Public.Users.SSN", "public.users.email"});
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setDeniedColumns(new String[] {"users.ssn"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        // A column is denied when any contributing grant denies it (#1099); the group's
        // schema-less users.ssn covers the direct grant's public.users.ssn.
        assertThat(view.deniedColumns()).containsExactly("public.users.email", "users.ssn");
    }

    @Test
    void permissiveGroupGrantCannotLiftADirectColumnDenial() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setDeniedColumns(new String[] {"public.users.ssn"});
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setCanWrite(true);
        group.setDeniedColumns(new String[0]);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.canWrite()).isTrue();
        assertThat(view.deniedColumns()).containsExactly("public.users.ssn");
        assertThat(com.bablsoft.accessflow.core.api.DeniedColumns.deniesColumn(
                view.deniedColumns(), "public", "users", "ssn")).isTrue();
    }

    @Test
    void groupColumnDenialBindsAMemberWhoseDirectGrantDeniesNothing() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setDeniedColumns(new String[] {"users.ssn"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        assertThat(service.findFor(userId, datasourceId).orElseThrow().deniedColumns())
                .containsExactly("users.ssn");
    }

    // ── Table / schema deny-lists (#939) ─────────────────────────────────────

    @Test
    void permissiveGroupGrantCannotLiftADirectTableDenial() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setDeniedTables(new String[] {"crm.salary"});
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setCanWrite(true);
        group.setCanDdl(true);
        group.setCanBreakGlass(true);
        group.setAllowedSchemas(new String[0]);
        group.setAllowedTables(new String[0]);
        group.setDeniedSchemas(new String[0]);
        group.setDeniedTables(new String[0]);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.canWrite()).isTrue();
        assertThat(view.allowedSchemas()).isEmpty();
        assertThat(view.allowedTables()).isEmpty();
        assertThat(view.deniedTables()).containsExactly("crm.salary");
        assertThat(com.bablsoft.accessflow.core.api.DeniedTables.rejected(view.deniedSchemas(),
                view.deniedTables(), java.util.Set.of("crm.salary", "crm.customer")))
                .containsExactly("crm.salary");
    }

    @Test
    void findForUnionsDenialsAcrossGrantsNormalisedAndDeduplicated() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setDeniedSchemas(new String[] {"\"HR\""});
        direct.setDeniedTables(new String[] {"CRM.Salary", " crm.salary "});
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setDeniedSchemas(new String[] {"hr", "audit"});
        group.setDeniedTables(new String[] {"crm.bonus", "crm.salary"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.deniedSchemas()).containsExactly("hr", "audit");
        assertThat(view.deniedTables()).containsExactly("crm.salary", "crm.bonus");
    }

    @Test
    void findForUnionsDeniedShapesAcrossGrantsSoAGroupGrantCannotLiftOne() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setDeniedShapes(new String[] {"JOIN", "CTE"});
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setDeniedShapes(new String[] {"UNION", "JOIN"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();
        var contributions = service.findContributions(userId, datasourceId);

        assertThat(view.deniedShapes()).containsExactly(QueryShape.JOIN, QueryShape.UNION, QueryShape.CTE);
        assertThat(contributions.get(0).deniedShapes()).containsExactly(QueryShape.JOIN, QueryShape.CTE);
        assertThat(contributions.get(1).deniedShapes()).containsExactly(QueryShape.JOIN, QueryShape.UNION);
    }

    @Test
    void findDirectForReadsTheStoredDeniedShapes() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setDeniedShapes(new String[] {"WINDOW_FUNCTION", "GROUP_BY"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));

        var view = service.findDirectFor(userId, datasourceId).orElseThrow();

        assertThat(view.deniedShapes()).containsExactly(QueryShape.GROUP_BY, QueryShape.WINDOW_FUNCTION);
    }

    @Test
    void findForDeniesNothingWhenNoGrantDeniesATable() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.deniedSchemas()).isEmpty();
        assertThat(view.deniedTables()).isEmpty();
        assertThat(view.deniedShapes()).isEmpty();
    }

    @Test
    void findDirectForNormalisesTheDenyLists() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setDeniedSchemas(new String[] {"HR", "hr"});
        direct.setDeniedTables(new String[] {"`CRM`.`Salary`"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));

        var view = service.findDirectFor(userId, datasourceId).orElseThrow();

        assertThat(view.deniedSchemas()).containsExactly("hr");
        assertThat(view.deniedTables()).containsExactly("crm.salary");
    }

    @Test
    void contributionsCarryEachGrantsOwnDenyLists() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setDeniedTables(new String[] {"crm.salary"});
        var group = newGroupPermission(groupId, datasourceId);
        group.setDeniedSchemas(new String[] {"hr"});
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var contributions = service.findContributions(userId, datasourceId);

        assertThat(contributions).hasSize(2);
        assertThat(contributions.get(0).deniedTables()).containsExactly("crm.salary");
        assertThat(contributions.get(0).deniedSchemas()).isEmpty();
        assertThat(contributions.get(1).deniedSchemas()).containsExactly("hr");
        assertThat(contributions.get(1).deniedTables()).isEmpty();
    }

    @Test
    void findForAllowListWideOpenWhenOneGrantHasNoRestriction() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setAllowedSchemas(new String[] {"public"});
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setAllowedSchemas(null); // all schemas allowed → union is wide open
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.allowedSchemas()).isEmpty();
    }

    @Test
    void findBreakGlassEligibleUnionsGroupGrants() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanBreakGlass(true);
        when(permissionRepository.findAllByUser_IdAndCanBreakGlassTrue(userId)).thenReturn(List.of());
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdInAndCanBreakGlassTrue(List.of(groupId)))
                .thenReturn(List.of(group));

        var eligible = service.findBreakGlassEligible(userId);

        assertThat(eligible).hasSize(1);
        assertThat(eligible.get(0).datasourceId()).isEqualTo(datasourceId);
        assertThat(eligible.get(0).canBreakGlass()).isTrue();
    }

    @Test
    void findDirectForIgnoresGroupGrants() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));

        var view = service.findDirectFor(userId, datasourceId).orElseThrow();

        assertThat(view.canRead()).isTrue();
    }

    // ── Row-limit override (#933) ─────────────────────────────────────────────

    @Test
    void findForCarriesTheDirectRowLimitOverride() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setRowLimitOverride(100);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(service.findFor(userId, datasourceId).orElseThrow().rowLimitOverride())
                .isEqualTo(100);
    }

    @Test
    void findForTakesAGroupRowLimitWhenTheDirectGrantSetsNone() {
        var view = mergeRowLimits(null, 100);

        assertThat(view.rowLimitOverride()).isEqualTo(100);
    }

    @Test
    void findForTakesTheSmallestRowLimitSoAGroupCannotRaiseATightDirectCap() {
        assertThat(mergeRowLimits(100, 500).rowLimitOverride()).isEqualTo(100);
        assertThat(mergeRowLimits(500, 100).rowLimitOverride()).isEqualTo(100);
    }

    @Test
    void findForRowLimitIsNullWhenNoGrantSetsOne() {
        assertThat(mergeRowLimits(null, null).rowLimitOverride()).isNull();
    }

    @Test
    void findForIgnoresTheRowLimitOfAnExpiredGrant() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setRowLimitOverride(500);
        var expired = newGroupPermission(groupId, datasourceId);
        expired.setCanRead(true);
        expired.setRowLimitOverride(10);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(expired));

        assertThat(service.findFor(userId, datasourceId).orElseThrow().rowLimitOverride())
                .isEqualTo(500);
    }

    @Test
    void findDirectForAndContributionsCarryTheRowLimitOverride() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setRowLimitOverride(250);
        var group = newGroupPermission(groupId, datasourceId);
        group.setRowLimitOverride(75);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        assertThat(service.findDirectFor(userId, datasourceId).orElseThrow().rowLimitOverride())
                .isEqualTo(250);
        var contributions = service.findContributions(userId, datasourceId);
        assertThat(contributions).extracting(c -> c.rowLimitOverride()).containsExactly(250, 75);
        assertThat(service.mergeContributions(contributions).orElseThrow().rowLimitOverride())
                .isEqualTo(75);
    }

    @Test
    void findForTakesTheSmallestBytesScannedCapAcrossGrants() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setBytesScannedLimitOverride(5_000L);
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setBytesScannedLimitOverride(2_000L);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        assertThat(service.findFor(userId, datasourceId).orElseThrow().bytesScannedLimitOverride())
                .isEqualTo(2_000L);
        assertThat(service.findDirectFor(userId, datasourceId).orElseThrow()
                .bytesScannedLimitOverride()).isEqualTo(5_000L);
        assertThat(service.findContributions(userId, datasourceId))
                .extracting(c -> c.bytesScannedLimitOverride()).containsExactly(5_000L, 2_000L);
    }

    @Test
    void findForBytesScannedCapIsNullWhenNoGrantSetsOneAndOneSetOneWins() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setBytesScannedLimitOverride(700L);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));

        assertThat(service.findFor(userId, datasourceId).orElseThrow().bytesScannedLimitOverride())
                .isEqualTo(700L);
    }

    private DatasourceUserPermissionView mergeRowLimits(
            Integer directLimit, Integer groupLimit) {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setRowLimitOverride(directLimit);
        var group = newGroupPermission(groupId, datasourceId);
        group.setCanRead(true);
        group.setRowLimitOverride(groupLimit);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(group));
        return service.findFor(userId, datasourceId).orElseThrow();
    }

    // ── Provenance (AF-859) ───────────────────────────────────────────────────

    @Test
    void findContributionsNamesTheDirectGrantAndEachGroupGrant() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var directId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var accessGrantRequestId = UUID.randomUUID();

        var direct = newPermission(directId, userId, datasourceId);
        direct.setCanRead(true);
        direct.setAllowedTables(new String[] {"orders"});
        direct.setAccessGrantRequestId(accessGrantRequestId);
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));

        var groupPermission = newGroupPermission(groupId, datasourceId);
        groupPermission.getGroup().setName("payments-oncall");
        groupPermission.setCanWrite(true);
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(groupPermission));

        var contributions = service.findContributions(userId, datasourceId);

        assertThat(contributions).hasSize(2);
        assertThat(contributions.get(0).sourceKind()).isEqualTo(DatasourcePermissionSourceKind.DIRECT);
        assertThat(contributions.get(0).sourceId()).isEqualTo(directId);
        assertThat(contributions.get(0).groupId()).isNull();
        assertThat(contributions.get(0).allowedTables()).containsExactly("orders");
        assertThat(contributions.get(0).accessGrantRequestId()).isEqualTo(accessGrantRequestId);
        assertThat(contributions.get(1).sourceKind()).isEqualTo(DatasourcePermissionSourceKind.GROUP);
        assertThat(contributions.get(1).accessGrantRequestId()).isNull();
        assertThat(contributions.get(1).groupId()).isEqualTo(groupId);
        assertThat(contributions.get(1).groupName()).isEqualTo("payments-oncall");
        assertThat(contributions.get(1).userId()).isEqualTo(userId);
        assertThat(contributions.get(1).canWrite()).isTrue();
    }

    @Test
    void findContributionsDropsExpiredGrants() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var expired = newPermission(UUID.randomUUID(), userId, datasourceId);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(expired));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of());

        assertThat(service.findContributions(userId, datasourceId)).isEmpty();
    }

    @Test
    void findContributionsIgnoresGroupGrantsOnOtherDatasources() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.empty());
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(newGroupPermission(groupId, UUID.randomUUID())));

        assertThat(service.findContributions(userId, datasourceId)).isEmpty();
    }

    @Test
    void findContributionsForDatasourceExpandsAGroupGrantAcrossItsMembers() {
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var memberA = UUID.randomUUID();
        var memberB = UUID.randomUUID();
        var directUser = UUID.randomUUID();

        var direct = newPermission(UUID.randomUUID(), directUser, datasourceId);
        direct.setCanRead(true);
        when(permissionRepository.findAllByDatasource_Id(datasourceId)).thenReturn(List.of(direct));

        var groupPermission = newGroupPermission(groupId, datasourceId);
        groupPermission.getGroup().setName("analysts");
        groupPermission.setCanWrite(true);
        when(groupPermissionRepository.findAllByDatasource_Id(datasourceId))
                .thenReturn(List.of(groupPermission));
        when(membershipRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(membership(groupId, memberA), membership(groupId, memberB)));

        var contributions = service.findContributionsForDatasource(datasourceId);

        assertThat(contributions).hasSize(3);
        assertThat(contributions).extracting("userId")
                .containsExactly(directUser, memberA, memberB);
        assertThat(contributions.get(1).groupName()).isEqualTo("analysts");
        assertThat(contributions.get(2).canWrite()).isTrue();
    }

    @Test
    void findContributionsForDatasourceDropsExpiredRowsOnBothSides() {
        var datasourceId = UUID.randomUUID();
        var expiredDirect = newPermission(UUID.randomUUID(), UUID.randomUUID(), datasourceId);
        expiredDirect.setExpiresAt(Instant.now().minusSeconds(60));
        var expiredGroup = newGroupPermission(UUID.randomUUID(), datasourceId);
        expiredGroup.setExpiresAt(Instant.now().minusSeconds(60));
        when(permissionRepository.findAllByDatasource_Id(datasourceId))
                .thenReturn(List.of(expiredDirect));
        when(groupPermissionRepository.findAllByDatasource_Id(datasourceId))
                .thenReturn(List.of(expiredGroup));

        assertThat(service.findContributionsForDatasource(datasourceId)).isEmpty();
    }

    @Test
    void findBreakGlassContributionsForOrganizationExpandsGroupsAndKeepsDirectRows() {
        var orgId = UUID.randomUUID();
        var datasourceA = UUID.randomUUID();
        var datasourceB = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var directUser = UUID.randomUUID();
        var memberA = UUID.randomUUID();
        var memberB = UUID.randomUUID();

        var direct = newPermission(UUID.randomUUID(), directUser, datasourceA);
        direct.setCanBreakGlass(true);
        direct.setExpiresAt(Instant.parse("2099-01-01T00:00:00Z"));
        when(permissionRepository.findAllByDatasource_Organization_IdAndCanBreakGlassTrue(orgId))
                .thenReturn(List.of(direct));

        var groupPermission = newGroupPermission(groupId, datasourceB);
        groupPermission.getGroup().setName("oncall");
        groupPermission.setCanBreakGlass(true);
        when(groupPermissionRepository.findAllByOrganizationIdAndCanBreakGlassTrue(orgId))
                .thenReturn(List.of(groupPermission));
        when(membershipRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(membership(groupId, memberA), membership(groupId, memberB)));

        var contributions = service.findBreakGlassContributionsForOrganization(orgId);

        assertThat(contributions).hasSize(3);
        assertThat(contributions.get(0).sourceKind()).isEqualTo(DatasourcePermissionSourceKind.DIRECT);
        assertThat(contributions.get(0).userId()).isEqualTo(directUser);
        assertThat(contributions.get(0).datasourceId()).isEqualTo(datasourceA);
        assertThat(contributions.get(0).expiresAt()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
        assertThat(contributions.get(0).canBreakGlass()).isTrue();
        assertThat(contributions).extracting("userId").containsExactly(directUser, memberA, memberB);
        assertThat(contributions.get(1).sourceKind()).isEqualTo(DatasourcePermissionSourceKind.GROUP);
        assertThat(contributions.get(1).groupId()).isEqualTo(groupId);
        assertThat(contributions.get(1).groupName()).isEqualTo("oncall");
        assertThat(contributions.get(2).datasourceId()).isEqualTo(datasourceB);
    }

    @Test
    void findBreakGlassContributionsForOrganizationDropsExpiredRowsAndSkipsMembershipQuery() {
        var orgId = UUID.randomUUID();
        var expiredDirect = newPermission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        expiredDirect.setCanBreakGlass(true);
        expiredDirect.setExpiresAt(Instant.now().minusSeconds(60));
        var expiredGroup = newGroupPermission(UUID.randomUUID(), UUID.randomUUID());
        expiredGroup.setCanBreakGlass(true);
        expiredGroup.setExpiresAt(Instant.now().minusSeconds(60));
        when(permissionRepository.findAllByDatasource_Organization_IdAndCanBreakGlassTrue(orgId))
                .thenReturn(List.of(expiredDirect));
        when(groupPermissionRepository.findAllByOrganizationIdAndCanBreakGlassTrue(orgId))
                .thenReturn(List.of(expiredGroup));

        assertThat(service.findBreakGlassContributionsForOrganization(orgId)).isEmpty();
        verifyNoInteractions(membershipRepository);
    }

    @Test
    void findBreakGlassContributionsForOrganizationYieldsNothingForAMemberlessGroup() {
        var orgId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var groupPermission = newGroupPermission(groupId, UUID.randomUUID());
        groupPermission.setCanBreakGlass(true);
        when(permissionRepository.findAllByDatasource_Organization_IdAndCanBreakGlassTrue(orgId))
                .thenReturn(List.of());
        when(groupPermissionRepository.findAllByOrganizationIdAndCanBreakGlassTrue(orgId))
                .thenReturn(List.of(groupPermission));
        when(membershipRepository.findAllByGroup_IdIn(List.of(groupId))).thenReturn(List.of());

        assertThat(service.findBreakGlassContributionsForOrganization(orgId)).isEmpty();
    }

    @Test
    void mergeContributionsAppliesTheSameRulesFindForDoes() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var direct = newPermission(UUID.randomUUID(), userId, datasourceId);
        direct.setCanRead(true);
        direct.setAllowedTables(new String[] {"orders"});
        direct.setRestrictedColumns(new String[] {"ssn", "email"});
        direct.setExpiresAt(Instant.parse("2026-10-01T00:00:00Z"));
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(direct));
        var groupId = UUID.randomUUID();
        var groupPermission = newGroupPermission(groupId, datasourceId);
        groupPermission.setCanWrite(true);
        groupPermission.setAllowedTables(new String[] {"payments"});
        groupPermission.setRestrictedColumns(new String[] {"ssn"});
        groupPermission.setExpiresAt(Instant.parse("2026-11-01T00:00:00Z"));
        when(membershipRepository.findGroupIdsForUser(userId)).thenReturn(List.of(groupId));
        when(groupPermissionRepository.findAllByGroup_IdIn(List.of(groupId)))
                .thenReturn(List.of(groupPermission));

        var merged = service.mergeContributions(service.findContributions(userId, datasourceId))
                .orElseThrow();
        var viaFindFor = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(merged).isEqualTo(viaFindFor);
        assertThat(merged.canRead()).isTrue();
        assertThat(merged.canWrite()).isTrue();
        assertThat(merged.allowedTables()).containsExactly("orders", "payments");
        assertThat(merged.restrictedColumns()).containsExactly("ssn");
        assertThat(merged.expiresAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void mergeContributionsOfNothingIsEmpty() {
        assertThat(service.mergeContributions(List.of())).isEmpty();
        assertThat(service.mergeContributions(null)).isEmpty();
    }

    private static UserGroupMembershipEntity membership(UUID groupId, UUID userId) {
        var user = new UserEntity();
        user.setId(userId);
        var group = new UserGroupEntity();
        group.setId(groupId);
        var membership = new UserGroupMembershipEntity();
        membership.setUser(user);
        membership.setGroup(group);
        return membership;
    }

    private static DatasourceUserPermissionEntity newPermission(UUID permId, UUID userId,
                                                                UUID datasourceId) {
        var user = new UserEntity();
        user.setId(userId);
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        var entity = new DatasourceUserPermissionEntity();
        entity.setId(permId);
        entity.setUser(user);
        entity.setDatasource(datasource);
        return entity;
    }

    private static DatasourceGroupPermissionEntity newGroupPermission(UUID groupId, UUID datasourceId) {
        var group = new UserGroupEntity();
        group.setId(groupId);
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        var entity = new DatasourceGroupPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setGroup(group);
        entity.setDatasource(datasource);
        return entity;
    }
}
