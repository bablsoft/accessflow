package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One suggestion on the wire (#776). Carries counts, never identities: who ran the query is
 * recorded in the audit log and used internally to rank, but is not the rail's business.
 */
public record QuerySuggestionResponse(UUID id, String sql, QueryType queryType,
                                      List<String> referencedTables, int approvedCount,
                                      int distinctSubmitterCount, Instant firstSubmittedAt,
                                      Instant lastSubmittedAt) {

    public static QuerySuggestionResponse from(QuerySuggestionView view) {
        return new QuerySuggestionResponse(view.id(), view.sqlText(), view.queryType(),
                view.referencedTables(), view.approvedCount(), view.distinctSubmitterCount(),
                view.firstSubmittedAt(), view.lastSubmittedAt());
    }
}
