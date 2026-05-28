package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the small core/api records and exceptions added under AF-353 for
 * the datasource_reviewers feature.
 */
class DatasourceReviewerDtosTest {

    @Test
    void userReviewerViewExposesAllFields() {
        var id = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var createdBy = UUID.randomUUID();
        var now = Instant.parse("2026-05-28T12:00:00Z");

        var view = new DatasourceReviewerView(id, datasourceId, userId,
                "alice@example.com", "Alice", null, null, createdBy, now);

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.datasourceId()).isEqualTo(datasourceId);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.userEmail()).isEqualTo("alice@example.com");
        assertThat(view.userDisplayName()).isEqualTo("Alice");
        assertThat(view.groupId()).isNull();
        assertThat(view.groupName()).isNull();
        assertThat(view.createdBy()).isEqualTo(createdBy);
        assertThat(view.createdAt()).isEqualTo(now);
    }

    @Test
    void groupReviewerViewExposesGroupFields() {
        var id = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var createdBy = UUID.randomUUID();
        var now = Instant.parse("2026-05-28T12:00:00Z");

        var view = new DatasourceReviewerView(id, datasourceId, null, null, null,
                groupId, "Reviewers", createdBy, now);

        assertThat(view.userId()).isNull();
        assertThat(view.userEmail()).isNull();
        assertThat(view.groupId()).isEqualTo(groupId);
        assertThat(view.groupName()).isEqualTo("Reviewers");
    }

    @Test
    void createDatasourceReviewerCommandRoundTripsFields() {
        var datasourceId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var createdBy = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var groupId = UUID.randomUUID();

        var command = new CreateDatasourceReviewerCommand(datasourceId, orgId, createdBy,
                userId, groupId);

        assertThat(command.datasourceId()).isEqualTo(datasourceId);
        assertThat(command.organizationId()).isEqualTo(orgId);
        assertThat(command.createdBy()).isEqualTo(createdBy);
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.groupId()).isEqualTo(groupId);
    }

    @Test
    void datasourceReviewerNotFoundCarriesId() {
        var id = UUID.randomUUID();
        var ex = new DatasourceReviewerNotFoundException(id);

        assertThat(ex.reviewerId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void datasourceReviewerAlreadyExistsCarriesDetail() {
        var ex = new DatasourceReviewerAlreadyExistsException("dup");

        assertThat(ex.getMessage()).isEqualTo("dup");
    }

    @Test
    void illegalDatasourceReviewerCarriesDetail() {
        var ex = new IllegalDatasourceReviewerException("xor required");

        assertThat(ex.getMessage()).isEqualTo("xor required");
    }
}
