package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeWebModelsTest {

    private final UUID id = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-09-22T10:15:00Z");

    @Test
    void responseMapsEveryFieldAndRendersWarnings() {
        var statement = new SchemaChangeSetStatementView(UUID.randomUUID(), 0, "CREATE TABLE t (id INT)",
                QueryType.DDL, at);
        var warning = new SchemaChangeStatementFinding(0, datasourceId,
                new SqlReviewFinding("ddl_statement", SqlReviewSeverity.WARN, 0, null, Map.of("statement_type", "CREATE TABLE")));
        var view = new SchemaChangeSetView(id, orgId, pipelineId, "cs", "desc", SchemaChangeSetStatus.ACTIVE,
                "a".repeat(64), userId, at, at.plusSeconds(1), List.of(statement), List.of(warning));

        var response = SchemaChangeSetResponse.from(view, f -> "rendered " + f.finding().ruleId());

        assertThat(response).extracting("id", "pipelineId", "name", "description", "status", "statementsChecksum",
                        "createdBy", "createdAt", "updatedAt")
                .containsExactly(id, pipelineId, "cs", "desc", SchemaChangeSetStatus.ACTIVE, "a".repeat(64), userId, at,
                        at.plusSeconds(1));
        assertThat(response.statements()).singleElement()
                .isEqualTo(new SchemaChangeSetStatementResponse(statement.id(), 0, "CREATE TABLE t (id INT)",
                        QueryType.DDL, at));
        assertThat(response.reviewWarnings()).singleElement()
                .isEqualTo(new SchemaChangeStatementFindingResponse(0, datasourceId, "ddl_statement",
                        SqlReviewSeverity.WARN, null, "rendered ddl_statement"));
    }

    @Test
    void responseWithoutStatementsOrWarningsHasEmptyLists() {
        var view = new SchemaChangeSetView(id, orgId, pipelineId, "cs", null, SchemaChangeSetStatus.DRAFT, null,
                null, at, at, null, null);

        var response = SchemaChangeSetResponse.from(view, f -> "unused");

        assertThat(response.statements()).isEmpty();
        assertThat(response.reviewWarnings()).isEmpty();
        assertThat(response.statementsChecksum()).isNull();
    }

    @Test
    void pageResponseMapsContentAndPagingFields() {
        var view = new SchemaChangeSetView(id, orgId, pipelineId, "cs", null, SchemaChangeSetStatus.DRAFT, null,
                null, at, at, null, null);

        var page = SchemaChangeSetPageResponse.from(new PageResponse<>(List.of(view), 2, 10, 21, 3), f -> "unused");

        assertThat(page.content()).extracting("id").containsExactly(id);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void requestRecordsConvertToCommandsPreservingOrder() {
        var create = new CreateSchemaChangeSetRequest(pipelineId, "cs", "d", List.of(
                new SchemaChangeSetStatementRequest("B"), new SchemaChangeSetStatementRequest("A")));
        var replace = new ReplaceSchemaChangeSetStatementsRequest(List.of(new SchemaChangeSetStatementRequest("C")));
        var update = new UpdateSchemaChangeSetRequest("n", null, SchemaChangeSetStatus.ARCHIVED);

        assertThat(create.toCommand().pipelineId()).isEqualTo(pipelineId);
        assertThat(create.toCommand().statements()).extracting("sqlText").containsExactly("B", "A");
        assertThat(new CreateSchemaChangeSetRequest(pipelineId, "cs", null, null).toCommand().statements()).isEmpty();
        assertThat(replace.toInputs()).extracting("sqlText").containsExactly("C");
        assertThat(update.toCommand()).extracting("name", "description", "status")
                .containsExactly("n", null, SchemaChangeSetStatus.ARCHIVED);
    }
}
