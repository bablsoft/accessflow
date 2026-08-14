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
     * Add a member with an explicit provenance (#621). Idempotent: when the user already has a
     * membership row in the group — whatever its source — that row is returned untouched
     * (first source wins).
     */
    UserGroupMembershipView addMember(UUID groupId, UUID userId, UUID organizationId,
                                      UserGroupMembershipSourceType source);

    /**
     * Remove the member's row only when it carries the given provenance (#621). A row of another
     * source, or no row at all, is a quiet no-op — SCIM must never delete MANUAL/IDP memberships.
     */
    void removeMemberBySource(UUID groupId, UUID userId, UUID organizationId,
                              UserGroupMembershipSourceType source);

    /**
     * Group-centric replace (#621): make exactly the given users the group's members of the given
     * provenance. Rows of other sources are untouched; users unknown in the organization are
     * skipped. Returns the user ids that now hold a row of that source in the group.
     */
    Set<UUID> replaceMembersBySource(UUID groupId, UUID organizationId, Collection<UUID> userIds,
                                     UserGroupMembershipSourceType source);

    /**
     * Replace this user's IDP-sourced group memberships with exactly the given set.
     * Memberships of every other source (MANUAL, SCIM) are left untouched. Returns the new
     * IDP-sourced membership set.
     */
    Set<UUID> syncIdpMemberships(UUID userId, UUID organizationId, Collection<UUID> groupIds);

    List<UUID> findGroupIdsForUser(UUID userId);
}
