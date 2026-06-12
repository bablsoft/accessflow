package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CassandraRowSecurityApplierTest {

    private static final UUID POLICY = UUID.randomUUID();
    private static final Set<String> KEY_COLUMNS = Set.of("tenant_id");

    private final CqlQueryParser parser = new CqlQueryParser(TestMessages.keyEcho());
    private final CassandraRowSecurityApplier applier =
            new CassandraRowSecurityApplier(TestMessages.keyEcho());

    private RowSecurityDirective directive(String table, String column, RowSecurityOperator op,
                                           List<Object> values) {
        return new RowSecurityDirective(POLICY, table, column, op, values);
    }

    @Test
    void noMatchingDirectiveIsNoOp() {
        var statement = parser.parseStatement("SELECT * FROM users WHERE id = 1");
        var applied = applier.apply(statement,
                List.of(directive("orders", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS);
        assertThat(applied.cql()).isEqualTo(statement.sql());
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void splicesKeyColumnEqualsIntoExistingWhere() {
        var statement = parser.parseStatement("SELECT * FROM users WHERE id = 1");
        var applied = applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS);
        assertThat(applied.cql())
                .contains("WHERE (id = 1) AND (\"tenant_id\" = :af_rls_0)");
        assertThat(applied.parameters()).containsEntry("af_rls_0", 7);
        assertThat(applied.appliedPolicyIds()).containsExactly(POLICY);
    }

    @Test
    void addsWhereWhenAbsent() {
        var statement = parser.parseStatement("SELECT * FROM users");
        var applied = applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS);
        assertThat(applied.cql()).contains("WHERE (\"tenant_id\" = :af_rls_0)");
    }

    @Test
    void splicesBeforeLimitTail() {
        var statement = parser.parseStatement("SELECT * FROM users LIMIT 10");
        var applied = applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS);
        assertThat(applied.cql()).contains("WHERE (\"tenant_id\" = :af_rls_0)").contains("LIMIT 10");
        assertThat(applied.cql().indexOf("WHERE")).isLessThan(applied.cql().indexOf("LIMIT"));
    }

    @Test
    void bindsInOperatorAsList() {
        var statement = parser.parseStatement("SELECT * FROM users WHERE id = 1");
        var applied = applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.IN, List.of(1, 2))),
                KEY_COLUMNS);
        assertThat(applied.cql()).contains("\"tenant_id\" IN :af_rls_0");
        assertThat(applied.parameters()).containsEntry("af_rls_0", List.of(1, 2));
    }

    @Test
    void failsClosedOnNonKeyColumn() {
        var statement = parser.parseStatement("SELECT * FROM users WHERE id = 1");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("users", "email", RowSecurityOperator.EQUALS, List.of("a"))),
                KEY_COLUMNS))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_cassandra_unrewritable");
    }

    @Test
    void failsClosedOnUnsupportedOperators() {
        var statement = parser.parseStatement("SELECT * FROM users WHERE id = 1");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.NOT_EQUALS, List.of(7))),
                KEY_COLUMNS)).isInstanceOf(UnrewritableRowSecurityException.class);
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.NOT_IN, List.of(7))),
                KEY_COLUMNS)).isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void failsClosedOnEmptyValues() {
        var statement = parser.parseStatement("SELECT * FROM users WHERE id = 1");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of())),
                KEY_COLUMNS)).isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void rejectsInsertIntoPolicedTable() {
        var statement = parser.parseStatement("INSERT INTO users (id) VALUES (1)");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_cassandra_insert_unsupported");
    }

    @Test
    void splicesUpdateAndDeleteOnKeyColumn() {
        var update = parser.parseStatement("UPDATE users SET name = 'a' WHERE id = 1");
        assertThat(applier.apply(update,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS).cql()).contains("\"tenant_id\" = :af_rls_0");
        var delete = parser.parseStatement("DELETE FROM users WHERE id = 1");
        assertThat(applier.apply(delete,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS).cql()).contains("\"tenant_id\" = :af_rls_0");
    }

    @Test
    void ddlIsUnaffected() {
        var statement = parser.parseStatement("TRUNCATE users");
        var applied = applier.apply(statement,
                List.of(directive("users", "tenant_id", RowSecurityOperator.EQUALS, List.of(7))),
                KEY_COLUMNS);
        assertThat(applied.cql()).isEqualTo(statement.sql());
        assertThat(applied.parameters()).isEmpty();
    }
}
