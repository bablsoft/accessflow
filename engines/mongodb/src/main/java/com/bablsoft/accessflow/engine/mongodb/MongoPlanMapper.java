package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.QueryPlanNode;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a MongoDB {@code explain} (queryPlanner verbosity) response into the engine-neutral
 * {@link QueryPlanNode} tree (issue AF-445). The response shape varies by operation and server
 * version, so we recursively locate the first {@code queryPlanner.winningPlan} and walk its
 * {@code stage}/{@code inputStage(s)} chain. Best-effort: returns {@code null} when no winning plan
 * is present (the caller still returns the raw JSON).
 */
final class MongoPlanMapper {

    private MongoPlanMapper() {
    }

    static QueryPlanNode toPlan(Document explainResponse, String collection) {
        Document winningPlan = findWinningPlan(explainResponse, 0);
        return winningPlan == null ? null : mapStage(winningPlan, collection);
    }

    private static Document findWinningPlan(Object node, int depth) {
        if (depth > 30 || !(node instanceof Document doc)) {
            return null;
        }
        var queryPlanner = doc.get("queryPlanner");
        if (queryPlanner instanceof Document qp && qp.get("winningPlan") instanceof Document wp) {
            return wp;
        }
        for (var value : doc.values()) {
            if (value instanceof Document child) {
                var found = findWinningPlan(child, depth + 1);
                if (found != null) {
                    return found;
                }
            } else if (value instanceof List<?> list) {
                for (var element : list) {
                    var found = findWinningPlan(element, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private static QueryPlanNode mapStage(Document stage, String collection) {
        String operation = stage.getString("stage");
        String indexName = stage.getString("indexName");
        String target = indexName != null ? indexName : collection;
        String detail = detail(stage);

        var children = new ArrayList<QueryPlanNode>();
        if (stage.get("inputStage") instanceof Document input) {
            children.add(mapStage(input, collection));
        }
        if (stage.get("inputStages") instanceof List<?> inputs) {
            for (var input : inputs) {
                if (input instanceof Document inputStage) {
                    children.add(mapStage(inputStage, collection));
                }
            }
        }
        return new QueryPlanNode(operation, target, null, null, detail, children);
    }

    private static String detail(Document stage) {
        if (stage.get("keyPattern") instanceof Document keyPattern) {
            return "keyPattern: " + keyPattern.toJson();
        }
        if (stage.get("filter") instanceof Document filter) {
            return "filter: " + filter.toJson();
        }
        return null;
    }
}
