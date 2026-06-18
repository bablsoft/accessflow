package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OracleDryRunPlannerTest {

    private final OracleDryRunPlanner planner = new OracleDryRunPlanner();
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private DryRunPlanRequest request(Connection connection) {
        return new DryRunPlanRequest(connection, "SELECT * FROM users", List.of(), QueryType.SELECT,
                "oracle", Duration.ofSeconds(30), Set.of(), clock.instant(), clock);
    }

    @Test
    void supportsOracleOnly() {
        assertThat(planner.supportedTypes()).containsExactly(DbType.ORACLE);
    }

    @Test
    void readsPlanTableIntoTreeAndCleansUp() throws SQLException {
        var connection = mock(Connection.class);
        var explain = mock(PreparedStatement.class);
        var select = mock(PreparedStatement.class);
        var delete = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.prepareStatement(startsWith("EXPLAIN PLAN"))).thenReturn(explain);
        when(connection.prepareStatement(startsWith("SELECT id"))).thenReturn(select);
        when(connection.prepareStatement(startsWith("DELETE"))).thenReturn(delete);
        when(select.executeQuery()).thenReturn(rs);

        when(rs.next()).thenReturn(true, true, false);
        when(rs.getInt("id")).thenReturn(0, 1);
        when(rs.getInt("parent_id")).thenReturn(0, 0);
        when(rs.getString("operation")).thenReturn("SELECT STATEMENT", "TABLE ACCESS");
        when(rs.getString("options")).thenReturn(null, "FULL");
        when(rs.getString("object_name")).thenReturn(null, "USERS");
        when(rs.getDouble("cardinality")).thenReturn(1000.0, 1000.0);
        when(rs.getDouble("cost")).thenReturn(50.0, 50.0);
        // wasNull() order per row: [parent?, cardinality?, cost?]
        when(rs.wasNull()).thenReturn(true, false, false, false, false, false);

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.estimatedRows()).isEqualTo(1000L);
        assertThat(result.plan().operation()).isEqualTo("SELECT STATEMENT");
        assertThat(result.plan().children()).hasSize(1);
        assertThat(result.plan().children().getFirst().operation()).isEqualTo("TABLE ACCESS FULL");
        assertThat(result.plan().children().getFirst().target()).isEqualTo("USERS");
        verify(delete).executeUpdate();
    }
}
