package com.bablsoft.accessflow.core.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface UserGroupService {

    PageResponse<UserGroupView> listGroups(UUID organizationId, PageRequest pageRequest);

    List<UserGroupView> listAll(UUID organizationId);

    UserGroupView getGroup(UUID groupId, UUID organizationId);

    UserGroupView createGroup(CreateUserGroupCommand command);

    UserGroupView updateGroup(UUID groupId, UUID organizationId, UpdateUserGroupCommand command);

    void deleteGroup(UUID groupId, UUID organizationId);

    List<UserGroupMembershipView> listMembers(UUID groupId, UUID organizationId);

    UserGroupMembershipView addMember(UUID groupId, UUID userId, UUID organizationId);

    void removeMember(UUID groupId, UUID userId, UUID organizationId);

    /**
     * Replace this user's IDP-sourced group memberships with exactly the given set.
     * MANUAL memberships are left untouched. Returns the new IDP-sourced membership set.
     */
    Set<UUID> syncIdpMemberships(UUID userId, UUID organizationId, Collection<UUID> groupIds);

    List<UUID> findGroupIdsForUser(UUID userId);
}
