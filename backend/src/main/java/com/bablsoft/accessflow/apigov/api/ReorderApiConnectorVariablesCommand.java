package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Command to reassign {@code sort_order} across a connector's variables (AF-613). The list is the
 * complete set of the connector's variable ids in the desired evaluation order; a partial or
 * unknown-id list is rejected.
 */
public record ReorderApiConnectorVariablesCommand(List<UUID> variableIds) {

    public ReorderApiConnectorVariablesCommand {
        variableIds = variableIds == null ? List.of() : List.copyOf(variableIds);
    }
}
