package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record SchemaChangeSetResponse(
        UUID id,
        UUID pipelineId,
        String name,
        String description,
        SchemaChangeSetStatus status,
        String statementsChecksum,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<SchemaChangeSetStatementResponse> statements,
        List<SchemaChangeStatementFindingResponse> reviewWarnings) {

    /** {@code render} turns a finding into the caller's-locale message — resolved at the web layer. */
    static SchemaChangeSetResponse from(SchemaChangeSetView view, Function<SchemaChangeStatementFinding, String> render) {
        return new SchemaChangeSetResponse(
                view.id(), view.pipelineId(), view.name(), view.description(), view.status(),
                view.statementsChecksum(), view.createdBy(), view.createdAt(), view.updatedAt(),
                view.statements().stream().map(SchemaChangeSetStatementResponse::from).toList(),
                view.reviewWarnings().stream()
                        .map(f -> SchemaChangeStatementFindingResponse.from(f, render.apply(f)))
                        .toList());
    }
}
