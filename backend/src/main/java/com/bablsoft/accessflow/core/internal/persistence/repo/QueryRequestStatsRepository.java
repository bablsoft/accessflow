package com.bablsoft.accessflow.core.internal.persistence.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Custom repository fragment for the datasource health aggregate. Implemented with a direct
 * {@code EntityManager} native query (not a Spring Data {@code @Query}) because the PostgreSQL
 * {@code FILTER (...)} and {@code percentile_cont(...) WITHIN GROUP (...)} clauses are not
 * understood by the JSqlParser-based query enhancer Spring Data selects when JSqlParser is on
 * the classpath — Hibernate passes native SQL through verbatim and sidesteps that parsing.
 */
public interface QueryRequestStatsRepository {

    List<DatasourceQueryStatsRow> aggregateByDatasource(Collection<UUID> datasourceIds, Instant since);
}
