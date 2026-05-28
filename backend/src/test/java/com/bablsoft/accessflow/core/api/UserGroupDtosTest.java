package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the small core/api records and exceptions added under AF-353.
 * Each record's accessors and each exception's accessor + message are
 * exercised here so the JaCoCo gate isn't tripped by trivial classes.
 */
class UserGroupDtosTest {

    @Test
    void userGroupViewExposesAllFields() {
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var now = Instant.parse("2026-05-28T12:00:00Z");
        var later = Instant.parse("2026-05-28T13:00:00Z");

        var view = new UserGroupView(id, orgId, "Eng", "Engineering team", 5L, now, later);

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.name()).isEqualTo("Eng");
        assertThat(view.description()).isEqualTo("Engineering team");
        assertThat(view.memberCount()).isEqualTo(5L);
        assertThat(view.createdAt()).isEqualTo(now);
        assertThat(view.updatedAt()).isEqualTo(later);
    }

    @Test
    void userGroupMembershipViewExposesAllFields() {
        var userId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var joined = Instant.parse("2026-05-28T14:00:00Z");

        var view = new UserGroupMembershipView(userId, groupId, "alice@example.com",
                "Alice", UserGroupMembershipSourceType.IDP, joined);

        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.groupId()).isEqualTo(groupId);
        assertThat(view.userEmail()).isEqualTo("alice@example.com");
        assertThat(view.userDisplayName()).isEqualTo("Alice");
        assertThat(view.source()).isEqualTo(UserGroupMembershipSourceType.IDP);
        assertThat(view.joinedAt()).isEqualTo(joined);
    }

    @Test
    void userGroupMembershipSourceTypeHasTwoValues() {
        assertThat(UserGroupMembershipSourceType.values())
                .containsExactly(UserGroupMembershipSourceType.MANUAL,
                        UserGroupMembershipSourceType.IDP);
        assertThat(UserGroupMembershipSourceType.valueOf("MANUAL"))
                .isEqualTo(UserGroupMembershipSourceType.MANUAL);
    }

    @Test
    void createUserGroupCommandRoundTripsFields() {
        var orgId = UUID.randomUUID();
        var command = new CreateUserGroupCommand(orgId, "Eng", "desc");
        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.name()).isEqualTo("Eng");
        assertThat(command.description()).isEqualTo("desc");
    }

    @Test
    void updateUserGroupCommandRoundTripsFields() {
        var command = new UpdateUserGroupCommand("Renamed", null);
        assertThat(command.name()).isEqualTo("Renamed");
        assertThat(command.description()).isNull();
    }

    @Test
    void userGroupNotFoundExceptionCarriesId() {
        var id = UUID.randomUUID();
        var ex = new UserGroupNotFoundException(id);

        assertThat(ex.groupId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void userGroupNameAlreadyExistsCarriesName() {
        var ex = new UserGroupNameAlreadyExistsException("Eng");

        assertThat(ex.name()).isEqualTo("Eng");
        assertThat(ex.getMessage()).contains("Eng");
    }

    @Test
    void userGroupMembershipNotFoundCarriesBothIds() {
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var ex = new UserGroupMembershipNotFoundException(groupId, userId);

        assertThat(ex.groupId()).isEqualTo(groupId);
        assertThat(ex.userId()).isEqualTo(userId);
        assertThat(ex.getMessage()).contains(userId.toString()).contains(groupId.toString());
    }
}
