package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.workflow.api.QuerySuggestionAggregationService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clustered-safe driver for the automatic query suggestion rebuild (#776). Every
 * {@code accessflow.workflow.query-suggestions.aggregation-poll-interval} it re-mines each
 * organisation's approved query history into the {@code query_suggestions} read model.
 *
 * <p>Thin by design: the organisation walk, the per-datasource transaction and the per-datasource
 * {@code RuntimeException} swallowing all live in
 * {@link QuerySuggestionAggregationService#aggregateAll()}. The {@code catch} here is not redundant
 * with those — paging the organisation list happens outside them, so a repository failure there
 * would otherwise escape into the scheduler.
 *
 * <p>Without {@code @SchedulerLock} every replica would re-mine the same history each tick and race
 * on the {@code @Version} column of every suggestion row it touched. {@code lockAtMostFor} is
 * generous relative to the work because the next tick is hours away: an over-long lock costs
 * nothing on the node-death path, while one that expires mid-run lets a second replica start a
 * concurrent rebuild and lose that race on every row.
 *
 * <p>A {@code fixedDelay} timer is per-replica and starts at context refresh, so an N-replica
 * cluster may rebuild up to N times per period and every pod start triggers a pass. That is load,
 * not incorrectness: the aggregation is deterministic, so a redundant pass over unchanged history
 * rewrites each row with the values it already held.
 */
@Component
@RequiredArgsConstructor
public class QuerySuggestionAggregationJob {

    private static final Logger log =
            LoggerFactory.getLogger(QuerySuggestionAggregationJob.class);

    private final QuerySuggestionAggregationService aggregationService;

    @Scheduled(fixedDelayString =
            "${accessflow.workflow.query-suggestions.aggregation-poll-interval:PT6H}")
    @SchedulerLock(name = "querySuggestionAggregationJob", lockAtMostFor = "PT1H",
            lockAtLeastFor = "PT5M")
    public void run() {
        try {
            aggregationService.aggregateAll();
        } catch (RuntimeException ex) {
            log.error("Query suggestion aggregation run failed", ex);
        }
    }
}
