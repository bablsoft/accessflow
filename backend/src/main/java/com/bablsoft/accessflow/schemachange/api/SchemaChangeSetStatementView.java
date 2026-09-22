package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Instant;
import java.util.UUID;

/** One ordered statement of a change set (#878, epic #870). */
public record SchemaChangeSetStatementView(
        UUID id,
        int sequenceOrder,
        String sqlText,
        QueryType queryType,
        Instant createdAt
) {
}
