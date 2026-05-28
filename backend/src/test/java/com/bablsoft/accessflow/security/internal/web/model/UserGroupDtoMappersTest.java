package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserGroupMembershipSourceType;
import com.bablsoft.accessflow.core.api.UserGroupMembershipView;
import com.bablsoft.accessflow.core.api.UserGroupView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the user-group request/response DTOs (AF-353).
 * They exercise the static {@code from(...)} mappers and the record
 * accessors to keep JaCoCo from flagging trivial mapping classes.
 */
class UserGroupDtoMappersTest {

    @Test
    void userGroupResponseFromViewCopiesAllFields() {
        var view = new UserGroupView(UUID.randomUUID(), UUID.randomUUID(), "Eng", "desc", 7L,
                Instant.parse("2026-05-28T12:00:00Z"), Instant.parse("2026-05-28T13:00:00Z"));

        var response = UserGroupResponse.from(view);

        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.organizationId()).isEqualTo(view.organizationId());
        assertThat(response.name()).isEqualTo("Eng");
        assertThat(response.description()).isEqualTo("desc");
        assertThat(response.memberCount()).isEqualTo(7L);
        assertThat(response.createdAt()).isEqualTo(view.createdAt());
        assertThat(response.updatedAt()).isEqualTo(view.updatedAt());
    }

    @Test
    void userGroupPageResponseFromPageCopiesAllFields() {
        var view = new UserGroupView(UUID.randomUUID(), UUID.randomUUID(), "Eng", null, 0L,
                Instant.parse("2026-05-28T12:00:00Z"), Instant.parse("2026-05-28T12:00:00Z"));
        var page = new PageResponse<>(List.of(UserGroupResponse.from(view)), 0, 20, 1L, 1);

        var response = UserGroupPageResponse.from(page);

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    void userGroupMemberResponseFromViewCopiesAllFields() {
        var userId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var joined = Instant.parse("2026-05-28T14:00:00Z");
        var view = new UserGroupMembershipView(userId, groupId, "alice@example.com",
                "Alice", UserGroupMembershipSourceType.MANUAL, joined);

        var response = UserGroupMemberResponse.from(view);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.groupId()).isEqualTo(groupId);
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.displayName()).isEqualTo("Alice");
        assertThat(response.source()).isEqualTo(UserGroupMembershipSourceType.MANUAL);
        assertThat(response.joinedAt()).isEqualTo(joined);
    }

    @Test
    void userGroupMemberListResponseExposesMembers() {
        var member = new UserGroupMemberResponse(UUID.randomUUID(), UUID.randomUUID(),
                "alice@example.com", "Alice", UserGroupMembershipSourceType.IDP, Instant.now());
        var list = new UserGroupMemberListResponse(List.of(member));

        assertThat(list.members()).containsExactly(member);
    }

    @Test
    void createUserGroupRequestRoundTrips() {
        var request = new CreateUserGroupRequest("Eng", "desc");
        assertThat(request.name()).isEqualTo("Eng");
        assertThat(request.description()).isEqualTo("desc");
    }

    @Test
    void updateUserGroupRequestRoundTrips() {
        var request = new UpdateUserGroupRequest("New name", null);
        assertThat(request.name()).isEqualTo("New name");
        assertThat(request.description()).isNull();
    }

    @Test
    void addUserGroupMemberRequestRoundTrips() {
        var userId = UUID.randomUUID();
        var request = new AddUserGroupMemberRequest(userId);
        assertThat(request.userId()).isEqualTo(userId);
    }
}
