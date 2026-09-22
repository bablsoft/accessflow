package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementView;

import java.time.Instant;
import java.util.UUID;

public record SchemaChangeSetStatementResponse(
        UUID id,
        int sequenceOrder,
        String sqlText,
        QueryType queryType,
        Instant createdAt) {

    static SchemaChangeSetStatementResponse from(SchemaChangeSetStatementView view) {
        return new SchemaChangeSetStatementResponse(view.id(), view.sequenceOrder(), view.sqlText(),
                view.queryType(), view.createdAt());
    }
}
