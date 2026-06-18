package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;

import java.sql.SQLException;
import java.util.Set;

/**
 * Strategy for retrieving a dialect's non-committing execution plan over JDBC (issue AF-445). One
 * implementation per relational dialect family; each is discovered as a Spring bean and registered
 * by the {@link DryRunPlannerRegistry} for the {@link DbType}s it declares. Implementations must
 * never execute or mutate the user's data — they only ask the engine to plan the statement.
 */
public interface DryRunPlanner {

    /** The relational {@link DbType}s this planner handles. */
    Set<DbType> supportedTypes();

    /** Run the dialect EXPLAIN and map it onto an engine-neutral {@link QueryDryRunResult}. */
    QueryDryRunResult plan(DryRunPlanRequest request) throws SQLException;
}
