package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.QueryType;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything a {@link DryRunPlanner} needs to run one dialect {@code EXPLAIN} (issue AF-445): the
 * acquired JDBC {@link Connection}, the row-security-rewritten SQL and its positional binds, the
 * query type, the engine id to stamp on the result, the statement timeout, the applied row-security
 * policy ids, and the clock + start instant for the duration measurement. The planner owns all
 * connection-level settings (read-only, session toggles) and any scratch-table cleanup.
 */
public record DryRunPlanRequest(Connection connection,
                                String sql,
                                List<Object> binds,
                                QueryType queryType,
                                String engineId,
                                Duration timeout,
                                Set<UUID> appliedRowSecurityPolicyIds,
                                Instant start,
                                Clock clock) {

    public Duration elapsed() {
        return Duration.between(start, clock.instant());
    }

    /**
     * Whether the dry-run connection may be marked read-only. Only for SELECT: strict engines
     * (MySQL) reject a write statement — even under a non-executing {@code EXPLAIN} — on a read-only
     * connection. The no-mutation guarantee for writes comes from EXPLAIN never executing, not from
     * this flag.
     */
    public boolean readOnlyEligible() {
        return queryType == QueryType.SELECT;
    }

    public int timeoutSeconds() {
        long seconds = timeout.toSeconds();
        return seconds <= 0 ? 1 : (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    /** Binds the row-security positional parameters onto a freshly prepared statement. */
    public void bind(java.sql.PreparedStatement statement) throws java.sql.SQLException {
        for (int i = 0; i < binds.size(); i++) {
            statement.setObject(i + 1, binds.get(i));
        }
    }
}
