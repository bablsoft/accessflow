package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresDryRunPlannerTest {

    private final PostgresDryRunPlanner planner = new PostgresDryRunPlanner(JsonMapper.builder().build());
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private DryRunPlanRequest request(Connection connection) {
        return new DryRunPlanRequest(connection, "SELECT * FROM users WHERE age > 21",
                List.of(), QueryType.SELECT, "postgresql", java.time.Duration.ofSeconds(30),
                Set.of(), clock.instant(), clock);
    }

    @Test
    void supportsPostgresqlOnly() {
        assertThat(planner.supportedTypes()).containsExactly(DbType.POSTGRESQL);
    }

    @Test
    void parsesJsonPlanTreeAndEstimate() throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("""
                [ { "Plan": {
                    "Node Type": "Seq Scan",
                    "Relation Name": "users",
                    "Plan Rows": 1000,
                    "Total Cost": 25.5,
                    "Filter": "(age > 21)",
                    "Plans": [ { "Node Type": "Index Scan", "Relation Name": "idx", "Plan Rows": 5 } ]
                } } ]""");

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.estimatedRows()).isEqualTo(1000L);
        assertThat(result.plan().operation()).isEqualTo("Seq Scan");
        assertThat(result.plan().target()).isEqualTo("users");
        assertThat(result.plan().estimatedCost()).isEqualTo(25.5);
        assertThat(result.plan().detail()).isEqualTo("(age > 21)");
        assertThat(result.plan().children()).hasSize(1);
        assertThat(result.plan().children().getFirst().operation()).isEqualTo("Index Scan");
        assertThat(result.rawPlan()).contains("Seq Scan");
        verify(connection).setReadOnly(true);
    }

    @Test
    void emptyResultSetYieldsSupportedWithNullPlan() throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.plan()).isNull();
        assertThat(result.estimatedRows()).isNull();
    }

    @Test
    void nonJsonPlanFallsBackToRawText() throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Seq Scan on users  (cost=0.00..25.50 rows=1000)");

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.plan()).isNull();
        assertThat(result.rawPlan()).contains("Seq Scan on users");
    }
}
