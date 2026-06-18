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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlDryRunPlannerTest {

    private final MySqlDryRunPlanner planner = new MySqlDryRunPlanner(JsonMapper.builder().build());
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private DryRunPlanRequest request(Connection connection) {
        return new DryRunPlanRequest(connection, "SELECT * FROM users", List.of(), QueryType.SELECT,
                "mysql", Duration.ofSeconds(30), Set.of(), clock.instant(), clock);
    }

    @Test
    void supportsMysqlAndMariadb() {
        assertThat(planner.supportedTypes()).containsExactlyInAnyOrder(DbType.MYSQL, DbType.MARIADB);
    }

    @Test
    void parsesQueryBlockTablesAndEstimate() throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("""
                { "query_block": {
                    "select_id": 1,
                    "cost_info": { "query_cost": "120.50" },
                    "table": {
                        "table_name": "users",
                        "access_type": "ALL",
                        "rows_examined_per_scan": 1000,
                        "rows_produced_per_join": 800,
                        "cost_info": { "read_cost": "100.00" },
                        "attached_condition": "(`db`.`users`.`age` > 21)"
                    }
                } }""");

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.estimatedRows()).isEqualTo(800L);
        assertThat(result.plan().operation()).isEqualTo("query_block");
        assertThat(result.plan().estimatedCost()).isEqualTo(120.50);
        assertThat(result.plan().children()).hasSize(1);
        var table = result.plan().children().getFirst();
        assertThat(table.operation()).isEqualTo("ALL");
        assertThat(table.target()).isEqualTo("users");
        assertThat(table.estimatedRows()).isEqualTo(800.0);
        assertThat(table.estimatedCost()).isEqualTo(100.0);
        assertThat(table.detail()).contains("age");
    }

    @Test
    void collectsNestedLoopTables() throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("""
                { "query_block": { "nested_loop": [
                    { "table": { "table_name": "a", "access_type": "ref", "rows_produced_per_join": 10 } },
                    { "table": { "table_name": "b", "access_type": "eq_ref", "rows_produced_per_join": 50 } }
                ] } }""");

        var result = planner.plan(request(connection));

        assertThat(result.estimatedRows()).isEqualTo(50L);
        assertThat(result.plan().children()).extracting(p -> p.target())
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void unparseableJsonStillReturnsRaw() throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("not json");

        var result = planner.plan(request(connection));

        assertThat(result.supported()).isTrue();
        assertThat(result.plan()).isNull();
        assertThat(result.rawPlan()).isEqualTo("not json");
    }
}
