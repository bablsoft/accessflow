package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * A table-scoped row-limit policy (#934) that targets a user (#946). It lowers the cap only for a
 * query that references its table; {@code schemaName} is {@code null} for a schema-less policy.
 */
public record ApplicableRowLimitPolicy(UUID policyId, String schemaName, String tableName,
                                       int maxRows, List<AccessTargetMatch> matchedBy) {

    public ApplicableRowLimitPolicy {
        matchedBy = matchedBy == null ? List.of() : List.copyOf(matchedBy);
    }
}
