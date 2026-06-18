package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.QueryPlanNode;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Maps an Elasticsearch / OpenSearch {@code _validate/query?explain=true} response into the
 * engine-neutral {@link QueryPlanNode} (issue AF-445). {@code _validate/query} is the non-executing
 * analogue of an EXPLAIN: it returns how the cluster interprets and rewrites the query (the Lucene
 * query string), with {@code valid: true/false} and a per-shard {@code explanations[].explanation}.
 * There is no row estimate, so the node carries no estimated rows.
 */
final class EsPlanMapper {

    private EsPlanMapper() {
    }

    static QueryPlanNode toPlan(JsonNode response, String index) {
        boolean valid = response.path("valid").isBoolean() && response.path("valid").booleanValue();
        return new QueryPlanNode(
                valid ? "valid_query" : "invalid_query",
                index,
                null,
                null,
                explanation(response),
                List.of());
    }

    private static String explanation(JsonNode response) {
        var explanations = response.path("explanations");
        if (explanations.isArray() && !explanations.isEmpty()) {
            var first = explanations.get(0).path("explanation");
            if (first.isString()) {
                return first.stringValue();
            }
        }
        var error = response.path("error").path("reason");
        return error.isString() ? error.stringValue() : null;
    }
}
