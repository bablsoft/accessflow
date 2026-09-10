package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionAggregationService;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Organisation fan-out for the automatic query suggestion rebuild (#776).
 *
 * <p>Thin by design: the per-organisation transaction and all of the mining live one level down in
 * {@link QuerySuggestionOrganizationAggregator}, reached across the Spring proxy so
 * {@code @Transactional} actually applies. What lives here is the master switch, the paged
 * organisation walk, and the per-organisation {@code RuntimeException} swallow — one organisation's
 * unparseable history must not cost every other organisation its suggestions.
 */
@Service
@RequiredArgsConstructor
class DefaultQuerySuggestionAggregationService implements QuerySuggestionAggregationService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultQuerySuggestionAggregationService.class);

    /** Organizations per page while fanning out; mirrors the approval-prediction retrain. */
    private static final int ORGANIZATION_PAGE_SIZE = 200;

    private final OrganizationAdminService organizationAdminService;
    private final QuerySuggestionCorpusLookupService corpusLookupService;
    private final QuerySuggestionDatasourceAggregator aggregator;
    private final QuerySuggestionProperties properties;
    private final Clock clock;

    @Override
    public void aggregateAll() {
        if (!properties.enabled()) {
            log.debug("Query suggestion aggregation skipped: feature disabled");
            return;
        }
        var runStamp = clock.instant();
        int page = 0;
        int aggregated = 0;
        int totalPages;
        do {
            // Sorted explicitly: each page is its own transaction, so an unsorted LIMIT/OFFSET
            // gives Postgres licence to reorder between pages and silently skip an organisation.
            var organizations = organizationAdminService.list(
                    PageRequest.of(page, ORGANIZATION_PAGE_SIZE, SortOrder.asc("id")));
            totalPages = organizations.totalPages();
            for (var organization : organizations.content()) {
                if (organization.disabled()) {
                    continue;
                }
                try {
                    aggregated += aggregateOrganization(organization.id(), runStamp);
                } catch (RuntimeException ex) {
                    log.error("Query suggestion aggregation failed for org {}", organization.id(),
                            ex);
                }
            }
            page++;
        } while (page < totalPages);
        log.info("Query suggestion aggregation complete for {} datasources", aggregated);
    }

    @Override
    public boolean aggregateDatasource(UUID organizationId, UUID datasourceId) {
        if (!properties.enabled()) {
            log.debug("Query suggestion recompute skipped: feature disabled");
            return false;
        }
        aggregator.aggregate(organizationId, datasourceId, clock.instant());
        return true;
    }

    /**
     * Walks the organisation's datasources that have qualifying history. Each datasource is its own
     * transaction and its own {@code RuntimeException} boundary: one datasource whose engine plugin
     * will not resolve must not cost the organisation every other datasource's suggestions.
     */
    private int aggregateOrganization(UUID organizationId, Instant runStamp) {
        var since = runStamp.minus(properties.lookback());
        int aggregated = 0;
        for (var datasourceId : corpusLookupService.findDatasourceIdsWithHistory(organizationId,
                since)) {
            try {
                aggregator.aggregate(organizationId, datasourceId, runStamp);
                aggregated++;
            } catch (RuntimeException ex) {
                log.error("Query suggestion aggregation failed for datasource {} in org {}",
                        datasourceId, organizationId, ex);
            }
        }
        return aggregated;
    }
}
