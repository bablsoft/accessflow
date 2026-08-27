package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlServerDryRunPlannerTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private final SqlServerDryRunPlanner planner = new SqlServerDryRunPlanner(messageSource);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private DryRunPlanRequest request(Connection connection) {
        return request(connection, List.of());
    }

    private DryRunPlanRequest request(Connection connection, List<Object> binds) {
        return new DryRunPlanRequest(connection, "SELECT * FROM users", binds, QueryType.SELECT,
                "mssql", Duration.ofSeconds(30), Set.of(), clock.instant(), clock);
    }

    @Test
    void supportsMssqlOnly() {
        assertThat(planner.supportedTypes()).containsExactly(DbType.MSSQL);
    }

    @Test
    void readsShowplanRowsIntoTreeAndTogglesOff() throws SQLException {
        var connection = mock(Connection.class);
        var toggleOn = mock(Statement.class);
        var statement = mock(Statement.class);
        var toggleOff = mock(Statement.class);
        var rs = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(toggleOn, statement, toggleOff);
        when(statement.executeQuery("SELECT * FROM users")).thenReturn(rs);

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
        // SET SHOWPLAN_ALL ON, the plan statement, then SET SHOWPLAN_ALL OFF
        verify(connection, times(3)).createStatement();
        verify(toggleOn).execute("SET SHOWPLAN_ALL ON");
        verify(statement).setQueryTimeout(30);
        verify(toggleOff).execute("SET SHOWPLAN_ALL OFF");
    }

    @Test
    void plansOverAPlainStatementNotAPreparedStatement() throws SQLException {
        // mssql-jdbc returns no SHOWPLAN rows at all over the prepared/RPC path (AF-762), so the
        // planner must never reach for prepareStatement. Guards against a well-meaning revert.
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rs = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.plan()).isNull();
        assertThat(result.estimatedRows()).isNull();
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void togglesShowplanOffWhenThePlanQueryFails() throws SQLException {
        var connection = mock(Connection.class);
        var toggleOn = mock(Statement.class);
        var statement = mock(Statement.class);
        var toggleOff = mock(Statement.class);
        when(connection.createStatement()).thenReturn(toggleOn, statement, toggleOff);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("boom"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> planner.plan(request(connection)))
                .isInstanceOf(SQLException.class);

        verify(toggleOff).execute("SET SHOWPLAN_ALL OFF");
    }

    @Test
    void aFailingShowplanResetIsWarnedAboutButDoesNotFailTheDryRun() throws SQLException {
        // The reset is best-effort — HikariCP does not clear arbitrary session state on return, so
        // a failure is worth a WARN, but it must not turn a successful plan into an error.
        var connection = mock(Connection.class);
        var toggleOn = mock(Statement.class);
        var statement = mock(Statement.class);
        var toggleOff = mock(Statement.class);
        var rs = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(toggleOn, statement, toggleOff);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        when(toggleOff.execute("SET SHOWPLAN_ALL OFF")).thenThrow(new SQLException("session gone"));

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        verify(toggleOff).execute("SET SHOWPLAN_ALL OFF");
    }

    @Test
    void rowSecurityBindsDegradeToUnsupportedWithoutTouchingTheSession() throws SQLException {
        var connection = mock(Connection.class);
        when(messageSource.getMessage(eq("error.dry_run.mssql_row_security_unsupported"),
                any(), any(Locale.class))).thenReturn("row security applies");

        var result = planner.plan(request(connection, List.of("eu-west")));

        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("mssql");
        assertThat(result.unsupportedReason()).isEqualTo("row security applies");
        // Fails closed before SET SHOWPLAN_ALL ON — the session is never mutated.
        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).setReadOnly(true);
    }
}
