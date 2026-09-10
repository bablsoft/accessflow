package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One automatic query suggestion (#776) as the editor renders it: a draft mined from the
 * organisation's own approved history, together with the evidence that makes it worth offering.
 *
 * <p>{@code sqlText} is the raw text of the most recent approved request in the group, not the
 * canonical form the group was keyed by — the canonical form is upper-cased and
 * whitespace-collapsed, which makes it a poor thing to load into an editor.
 *
 * <p>{@code score} is the heuristic rank the caller was served in; it is advisory and carries no
 * governance meaning. Applying a suggestion submits it through the normal pipeline, where it is
 * analysed and reviewed like any other query.
 */
public record QuerySuggestionView(
        UUID id,
        String sqlText,
        QueryType queryType,
        List<String> referencedTables,
        int approvedCount,
        int distinctSubmitterCount,
        Instant firstSubmittedAt,
        Instant lastSubmittedAt,
        double score) {

    public QuerySuggestionView {
        referencedTables = referencedTables == null ? List.of() : List.copyOf(referencedTables);
    }
}
