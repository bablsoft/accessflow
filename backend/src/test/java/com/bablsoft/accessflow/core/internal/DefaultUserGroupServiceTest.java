package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateUserGroupCommand;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UpdateUserGroupCommand;
import com.bablsoft.accessflow.core.api.UserGroupMembershipNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.UserGroupNotFoundException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipSource;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceReviewerRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserGroupServiceTest {

    @Mock UserGroupRepository userGroupRepository;
    @Mock UserGroupMembershipRepository membershipRepository;
    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock DatasourceReviewerRepository datasourceReviewerRepository;

    private DefaultUserGroupService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultUserGroupService(userGroupRepository, membershipRepository,
                userRepository, organizationRepository, datasourceReviewerRepository);
    }

    @Test
    void listGroupsReturnsPageWithCounts() {
        var group = group("Engineers");
        Page<UserGroupEntity> page = new PageImpl<>(List.of(group));
        when(userGroupRepository.findAllByOrganization_Id(eq(orgId), any(Pageable.class)))
                .thenReturn(page);
        when(membershipRepository.countByGroup_Id(group.getId())).thenReturn(3L);

        var result = service.listGroups(orgId, new PageRequest(0, 20, null));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).name()).isEqualTo("Engineers");
        assertThat(result.content().get(0).memberCount()).isEqualTo(3L);
    }

    @Test
    void createGroupRejectsDuplicateName() {
        var existing = group("Engineers");
        when(userGroupRepository.findByOrganizationIdAndNameIgnoreCase(orgId, "engineers"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createGroup(
                new CreateUserGroupCommand(orgId, "engineers", null)))
                .isInstanceOf(UserGroupNameAlreadyExistsException.class);
        verify(userGroupRepository, never()).save(any());
    }

    @Test
    void createGroupPersistsAndReturnsZeroMembers() {
        when(userGroupRepository.findByOrganizationIdAndNameIgnoreCase(orgId, "Ops"))
                .thenReturn(Optional.empty());
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(userGroupRepository.save(any(UserGroupEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.createGroup(new CreateUserGroupCommand(orgId, "Ops", "Ops team"));

        assertThat(view.name()).isEqualTo("Ops");
        assertThat(view.description()).isEqualTo("Ops team");
        assertThat(view.memberCount()).isZero();
    }

    @Test
    void getGroupThrowsWhenNotInOrganization() {
        var otherOrg = new OrganizationEntity();
        otherOrg.setId(UUID.randomUUID());
        var entity = new UserGroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(otherOrg);
        when(userGroupRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getGroup(entity.getId(), orgId))
                .isInstanceOf(UserGroupNotFoundException.class);
    }

    @Test
    void addMemberRejectsCrossOrgUser() {
        var group = group("Engineers");
        when(userGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        var foreignUser = user(UUID.randomUUID(), UUID.randomUUID());
        when(userRepository.findById(foreignUser.getId())).thenReturn(Optional.of(foreignUser));

        assertThatThrownBy(() -> service.addMember(group.getId(), foreignUser.getId(), orgId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void addMemberPersistsManualMembership() {
        var group = group("Engineers");
        when(userGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        var user = user(UUID.randomUUID(), orgId);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(membershipRepository.existsByUser_IdAndGroup_Id(user.getId(), group.getId()))
                .thenReturn(false);
        when(membershipRepository.save(any(UserGroupMembershipEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.addMember(group.getId(), user.getId(), orgId);

        assertThat(view.userId()).isEqualTo(user.getId());
        assertThat(view.source().name()).isEqualTo("MANUAL");
    }

    @Test
    void removeMemberThrowsWhenNotMember() {
        var group = group("Engineers");
        when(userGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(membershipRepository.existsByUser_IdAndGroup_Id(any(UUID.class), eq(group.getId())))
                .thenReturn(false);

        assertThatThrownBy(() -> service.removeMember(group.getId(), UUID.randomUUID(), orgId))
                .isInstanceOf(UserGroupMembershipNotFoundException.class);
    }

    @Test
    void deleteGroupCascadesToMembershipsAndReviewers() {
        var group = group("Engineers");
        when(userGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));

        service.deleteGroup(group.getId(), orgId);

        verify(datasourceReviewerRepository).deleteByGroupId(group.getId());
        verify(membershipRepository).deleteByGroupId(group.getId());
        verify(userGroupRepository).delete(group);
    }

    @Test
    void updateGroupRejectsConflictingRename() {
        var group = group("Engineers");
        when(userGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        var other = group("Ops");
        when(userGroupRepository.findByOrganizationIdAndNameIgnoreCase(orgId, "Ops"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.updateGroup(group.getId(), orgId,
                new UpdateUserGroupCommand("Ops", null)))
                .isInstanceOf(UserGroupNameAlreadyExistsException.class);
    }

    @Test
    void syncIdpMembershipsReplacesOnlyIdpRows() {
        var userId = UUID.randomUUID();
        var manualGroupId = UUID.randomUUID();
        var idpKeepGroupId = UUID.randomUUID();
        var idpDropGroupId = UUID.randomUUID();
        var idpAddGroupId = UUID.randomUUID();

        var manualMembership = membership(userId, manualGroupId, UserGroupMembershipSource.MANUAL);
        var idpKeepMembership = membership(userId, idpKeepGroupId, UserGroupMembershipSource.IDP);
        var idpDropMembership = membership(userId, idpDropGroupId, UserGroupMembershipSource.IDP);
        when(membershipRepository.findAllByUser_Id(userId))
                .thenReturn(List.of(manualMembership, idpKeepMembership, idpDropMembership));
        var addGroup = group("New");
        addGroup.setId(idpAddGroupId);
        when(userGroupRepository.findById(idpAddGroupId)).thenReturn(Optional.of(addGroup));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, orgId)));

        var result = service.syncIdpMemberships(userId, orgId,
                Set.of(idpKeepGroupId, idpAddGroupId));

        verify(membershipRepository).delete(idpDropMembership);
        verify(membershipRepository).save(any(UserGroupMembershipEntity.class));
        assertThat(result).containsExactlyInAnyOrder(idpKeepGroupId, idpAddGroupId);
    }

    private UserGroupEntity group(String name) {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var entity = new UserGroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(org);
        entity.setName(name);
        return entity;
    }

    private UserEntity user(UUID userId, UUID userOrgId) {
        var org = new OrganizationEntity();
        org.setId(userOrgId);
        var entity = new UserEntity();
        entity.setId(userId);
        entity.setOrganization(org);
        entity.setEmail(userId + "@example.com");
        return entity;
    }

    private UserGroupMembershipEntity membership(UUID userId, UUID groupId,
                                                 UserGroupMembershipSource source) {
        var entity = new UserGroupMembershipEntity();
        entity.setId(new UserGroupMembershipEntity.Id(userId, groupId));
        entity.setUser(user(userId, orgId));
        var g = group("G-" + groupId);
        g.setId(groupId);
        entity.setGroup(g);
        entity.setSource(source);
        return entity;
    }
}
