package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
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

    // ── Provenance (AF-859) ───────────────────────────────────────────────────

    @Test
    void findContributionsNamesTheDirectGrantAndEachGroupGrant() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var directId = UUID.randomUUID();
        var groupId = UUID.randomUUID();

        var direct = newPermission(directId, userId, datasourceId);
        direct.setCanRead(true);
        direct.setAllowedTables(new String[] {"orders"});
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
        assertThat(contributions.get(1).sourceKind()).isEqualTo(DatasourcePermissionSourceKind.GROUP);
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
        when(membershipRepository.findAllByGroup_Id(groupId))
                .thenReturn(List.of(membership(memberA), membership(memberB)));

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

    private static UserGroupMembershipEntity membership(UUID userId) {
        var user = new UserEntity();
        user.setId(userId);
        var membership = new UserGroupMembershipEntity();
        membership.setUser(user);
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
