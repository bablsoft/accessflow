package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionService;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionView;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySuggestionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySuggestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Serves the precomputed suggestion rail for one datasource (#776), filtered to what the viewer may
 * actually reach.
 *
 * <p>The filter runs in a deliberate order, and each step answers a different question.
 *
 * <ol>
 *   <li><strong>Can the viewer see the datasource at all?</strong> Resolved through
 *       {@code DatasourceAdminService}, which throws {@code DatasourceNotFoundException} — 404,
 *       never 403, so the endpoint cannot be used to probe for datasources.</li>
 *   <li><strong>Do they hold an unexpired grant on it?</strong> If not, an <em>empty rail</em>. In
 *       practice step 1 has already answered 404 for such a viewer — datasource visibility is
 *       itself grant-based — so this is the fail-closed guard for the narrow race where a grant
 *       lapses between the two reads, not the common path. It returns empty rather than throwing
 *       because by then the caller has been told the datasource exists.</li>
 *   <li><strong>Does the grant carry the capability the shape needs?</strong> A read-only analyst
 *       is never shown a DDL suggestion they could not submit.</li>
 *   <li><strong>Is every referenced table inside their allow-list?</strong> This is the step the
 *       whole feature turns on: a suggestion is another analyst's approved SQL, so offering one for
 *       a table the viewer is not allow-listed for would disclose that the table exists. It is safe
 *       here only because the aggregation guarantees {@code referenced_tables} is never empty —
 *       {@code DatasourcePermissionChecker.rejectedTables} reports "nothing rejected" for an empty
 *       set, so an unresolved row would clear this check for everyone. Do not relax that guard.</li>
 * </ol>
 *
 * <p>A {@code QUERY_ADMIN} caller skips steps 2–4, mirroring
 * {@code DefaultQuerySubmissionService}: that permission already means "submit against any
 * datasource without a per-resource grant", so filtering their rail would hide queries they can run
 * today.
 *
 * <p>Ranking happens after filtering, over the surviving rows only, so a heavily-scoped analyst
 * still gets a full rail instead of the leftovers of an org-wide top ten. The per-viewer overlap
 * term reads {@code submitter_ids} off rows already in hand — no second query, and above all no
 * {@code QueryParser} call, which for a NoSQL engine would mean a plugin dispatch per rail open.
 */
@Service
@RequiredArgsConstructor
class DefaultQuerySuggestionService implements QuerySuggestionService {

    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final QuerySuggestionRepository suggestionRepository;
    private final QuerySuggestionScorer scorer;
    private final QuerySuggestionProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<QuerySuggestionView> findForViewer(UUID datasourceId, UUID organizationId,
                                                   UUID viewerUserId, boolean viewerIsQueryAdmin,
                                                   int limit) {
        // Step 1 runs even when the feature is disabled, so a disabled install answers 404 for an
        // invisible datasource rather than leaking the difference between "off" and "not yours".
        if (viewerIsQueryAdmin) {
            datasourceAdminService.getForAdmin(datasourceId, organizationId);
        } else {
            datasourceAdminService.getForUser(datasourceId, organizationId, viewerUserId);
        }
        if (!properties.enabled()) {
            return List.of();
        }

        DatasourceUserPermissionView permission = null;
        if (!viewerIsQueryAdmin) {
            permission = permissionLookupService.findFor(viewerUserId, datasourceId).orElse(null);
            if (permission == null || isExpired(permission)) {
                return List.of();
            }
        }

        var rows = suggestionRepository
                .findByDatasourceIdOrderByApprovedCountDescLastSubmittedAtDesc(datasourceId);
        var viewerTables = affinityFor(rows, viewerUserId);
        var now = clock.instant();

        var visible = new ArrayList<QuerySuggestionView>();
        for (var row : rows) {
            var tables = Arrays.asList(row.getReferencedTables());
            // Belt for the invariant the aggregation enforces: rejectedTables() reports "nothing
            // rejected" for an empty set, so a row that somehow reached the table with no resolved
            // tables would clear the allow-list for every viewer. One guard is not enough for the
            // whole feature's disclosure story.
            if (tables.isEmpty()) {
                continue;
            }
            if (permission != null && !isReachable(permission, row, tables)) {
                continue;
            }
            visible.add(toView(row, scorer.score(row.getApprovedCount(), row.getLastSubmittedAt(),
                    now, new HashSet<>(tables), viewerTables)));
        }
        visible.sort(Comparator.comparingDouble(QuerySuggestionView::score).reversed());
        int capped = Math.min(visible.size(), clampLimit(limit));
        return List.copyOf(visible.subList(0, capped));
    }

    private boolean isExpired(DatasourceUserPermissionView permission) {
        return permission.expiresAt() != null && permission.expiresAt().isBefore(clock.instant());
    }

    private boolean isReachable(DatasourceUserPermissionView permission,
                                QuerySuggestionEntity row, List<String> tables) {
        return DatasourcePermissionChecker.hasCapability(permission, row.getQueryType())
                && DatasourcePermissionChecker.rejectedTables(permission, new HashSet<>(tables))
                        .isEmpty();
    }

    /**
     * The tables the viewer has themselves been working in on this datasource, taken from the
     * suggestions their own approved queries contributed to. Empty for a viewer with no history,
     * which simply drops the overlap term rather than substituting a guess.
     */
    private Set<String> affinityFor(List<QuerySuggestionEntity> rows, UUID viewerUserId) {
        var tables = new HashSet<String>();
        for (var row : rows) {
            for (UUID submitterId : row.getSubmitterIds()) {
                if (viewerUserId.equals(submitterId)) {
                    tables.addAll(Arrays.asList(row.getReferencedTables()));
                    break;
                }
            }
        }
        return tables;
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return properties.defaultLimit();
        }
        return Math.min(limit, properties.maxLimit());
    }

    private QuerySuggestionView toView(QuerySuggestionEntity row, double score) {
        return new QuerySuggestionView(row.getId(), row.getSqlText(), row.getQueryType(),
                List.of(row.getReferencedTables()), row.getApprovedCount(),
                row.getDistinctSubmitterCount(), row.getFirstSubmittedAt(),
                row.getLastSubmittedAt(), score);
    }
}
