package com.bablsoft.accessflow.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Serves the precomputed automatic query suggestions (#776) for one datasource, filtered to what
 * the calling viewer may actually reach.
 *
 * <p>The filter is the point of the service, so it lives here rather than in the controller: a
 * suggestion is another analyst's approved SQL, and surfacing one for a table the viewer is not
 * allow-listed for would tell them that table exists. Candidates are dropped unless the viewer's
 * effective permission grants the capability the query type needs and covers every table the query
 * references. A viewer with no effective permission — or an expired one — gets an empty page rather
 * than a 403, because a 403 would itself confirm the datasource has suggestions to hide.
 *
 * <p>Advisory only. Nothing here touches routing policies, grant-covered auto-approval, or any
 * decision path, and being mined from an approved query grants a suggestion nothing when it is
 * submitted.
 */
public interface QuerySuggestionService {

    /**
     * Ranked suggestions for {@code datasourceId}, highest score first. A capped rail rather than a
     * paginated collection: nobody pages through suggestions, and an offset into a ranking that is
     * recomputed per viewer would not be stable anyway.
     *
     * @param viewerIsQueryAdmin whether the caller holds {@code QUERY_ADMIN}, which already permits
     *                           submitting against any datasource without a per-resource grant;
     *                           such a caller skips the per-table filter exactly as the submission
     *                           path skips its permission verify.
     * @return the suggestions, or an empty list when the caller holds no unexpired grant on the
     *         datasource — an empty rail, not an error: the datasource is visible to them, and a
     *         403 on a URL that just answered 200 for its own existence would be incoherent.
     * @throws com.bablsoft.accessflow.core.api.DatasourceNotFoundException if the datasource does
     *         not exist in the caller's organisation or the caller cannot see it.
     */
    List<QuerySuggestionView> findForViewer(UUID datasourceId, UUID organizationId,
                                            UUID viewerUserId, boolean viewerIsQueryAdmin,
                                            int limit);
}
