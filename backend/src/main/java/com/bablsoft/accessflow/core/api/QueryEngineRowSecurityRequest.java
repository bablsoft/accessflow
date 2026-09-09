package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One offline row-security classification request (issue AF-630): "given these resolved directives,
 * what would you do to this query?". Carries no connection descriptor and no credentials, because
 * {@link QueryEngine#classifyRowSecurity(QueryEngineRowSecurityRequest)} must answer without
 * reaching the datasource.
 *
 * @param datasourceId the datasource the query was submitted against, for the engine's own logging
 * @param query        the engine-native query text, exactly as it was submitted
 * @param directives   the row-security predicates already resolved for one submitter; an empty
 *                     {@code values} list on a directive is the fail-closed deny signal
 */
public record QueryEngineRowSecurityRequest(UUID datasourceId, String query,
                                            List<RowSecurityDirective> directives) {

    public QueryEngineRowSecurityRequest {
        Objects.requireNonNull(query, "query");
        directives = directives == null ? List.of() : List.copyOf(directives);
    }
}
