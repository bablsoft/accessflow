package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DatasourceReviewerView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the datasource-reviewer request/response DTOs (AF-353).
 */
class DatasourceReviewerDtoMappersTest {

    @Test
    void datasourceReviewerResponseFromUserViewCopiesAllFields() {
        var view = new DatasourceReviewerView(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "alice@example.com", "Alice", null, null,
                UUID.randomUUID(), Instant.parse("2026-05-28T12:00:00Z"));

        var response = DatasourceReviewerResponse.from(view);

        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.datasourceId()).isEqualTo(view.datasourceId());
        assertThat(response.userId()).isEqualTo(view.userId());
        assertThat(response.userEmail()).isEqualTo("alice@example.com");
        assertThat(response.userDisplayName()).isEqualTo("Alice");
        assertThat(response.groupId()).isNull();
        assertThat(response.groupName()).isNull();
        assertThat(response.createdBy()).isEqualTo(view.createdBy());
        assertThat(response.createdAt()).isEqualTo(view.createdAt());
    }

    @Test
    void datasourceReviewerResponseFromGroupViewCopiesAllFields() {
        var groupId = UUID.randomUUID();
        var view = new DatasourceReviewerView(UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, groupId, "Reviewers", UUID.randomUUID(),
                Instant.parse("2026-05-28T12:00:00Z"));

        var response = DatasourceReviewerResponse.from(view);

        assertThat(response.userId()).isNull();
        assertThat(response.groupId()).isEqualTo(groupId);
        assertThat(response.groupName()).isEqualTo("Reviewers");
    }

    @Test
    void datasourceReviewerListResponseExposesReviewers() {
        var reviewer = DatasourceReviewerResponse.from(new DatasourceReviewerView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "alice@example.com", "Alice", null, null, UUID.randomUUID(), Instant.now()));
        var list = new DatasourceReviewerListResponse(List.of(reviewer));

        assertThat(list.reviewers()).containsExactly(reviewer);
    }

    @Test
    void createDatasourceReviewerRequestRoundTrips() {
        var userId = UUID.randomUUID();
        var groupId = UUID.randomUUID();

        var userReq = new CreateDatasourceReviewerRequest(userId, null);
        assertThat(userReq.userId()).isEqualTo(userId);
        assertThat(userReq.groupId()).isNull();

        var groupReq = new CreateDatasourceReviewerRequest(null, groupId);
        assertThat(groupReq.userId()).isNull();
        assertThat(groupReq.groupId()).isEqualTo(groupId);
    }
}
