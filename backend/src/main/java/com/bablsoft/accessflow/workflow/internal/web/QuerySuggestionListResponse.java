package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.QuerySuggestionView;

import java.util.List;

/**
 * The suggestion rail (#776). An envelope rather than a bare array so the response can grow a field
 * later without breaking clients, and deliberately not a page: the list is capped, and an offset
 * into a ranking recomputed per viewer would not be stable.
 */
public record QuerySuggestionListResponse(List<QuerySuggestionResponse> suggestions) {

    public static QuerySuggestionListResponse from(List<QuerySuggestionView> views) {
        return new QuerySuggestionListResponse(
                views.stream().map(QuerySuggestionResponse::from).toList());
    }
}
