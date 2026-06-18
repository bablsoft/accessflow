package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlServerDryRunPlannerTest {

    private final SqlServerDryRunPlanner planner = new SqlServerDryRunPlanner();
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private DryRunPlanRequest request(Connection connection) {
        return new DryRunPlanRequest(connection, "SELECT * FROM users", List.of(), QueryType.SELECT,
                "mssql", Duration.ofSeconds(30), Set.of(), clock.instant(), clock);
    }

    @Test
    void supportsMssqlOnly() {
        assertThat(planner.supportedTypes()).containsExactly(DbType.MSSQL);
    }

    @Test
    void readsShowplanRowsIntoTreeAndTogglesOff() throws SQLException {
        var connection = mock(Connection.class);
        var toggle = mock(Statement.class);
        var statement = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(toggle);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);

        when(rs.next()).thenReturn(true, true, false);
        when(rs.getInt("NodeId")).thenReturn(1, 2);
        when(rs.getInt("Parent")).thenReturn(0, 1);
        when(rs.getString("StmtText")).thenReturn("SELECT * FROM users", "  |--Table Scan");
        when(rs.getString("PhysicalOp")).thenReturn(null, "Table Scan");
        when(rs.getString("Argument")).thenReturn(null, "OBJECT:([users])");
        when(rs.getDouble("EstimateRows")).thenReturn(1000.0, 1000.0);
        when(rs.getDouble("TotalSubtreeCost")).thenReturn(0.5, 0.4);
        when(rs.wasNull()).thenReturn(false, false, false, false);

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.estimatedRows()).isEqualTo(1000L);
        assertThat(result.plan().operation()).isEqualTo("SELECT * FROM users");
        assertThat(result.plan().children()).hasSize(1);
        assertThat(result.plan().children().getFirst().operation()).isEqualTo("Table Scan");
        assertThat(result.plan().children().getFirst().detail()).isEqualTo("OBJECT:([users])");
        // SET SHOWPLAN_ALL ON then OFF
        verify(connection, times(2)).createStatement();
        verify(toggle).execute("SET SHOWPLAN_ALL ON");
        verify(toggle).execute("SET SHOWPLAN_ALL OFF");
    }
}
