package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BigQueryRowSecurityApplierTest {

    private final BigQueryQueryParser parser = new BigQueryQueryParser(TestMessages.keyEcho());
    private final BigQueryRowSecurityApplier applier =
            new BigQueryRowSecurityApplier(TestMessages.keyEcho());

    private static RowSecurityDirective directive(String table, String column,
                                                  RowSecurityOperator operator, Object... values) {
        return new RowSecurityDirective(UUID.randomUUID(), table, column, operator,
                List.of(values));
    }

    private BigQueryRowSecurityApplier.Applied apply(String sql, RowSecurityDirective... directives) {
        return applier.apply(parser.parseStatement(sql), List.of(directives));
    }

    // ---- no-op paths ---------------------------------------------------------------------------

    @Test
    void noDirectivesLeavesStatementUnchanged() {
        var applied = apply("SELECT * FROM ds.users");
        assertThat(applied.statement()).isEqualTo("SELECT * FROM ds.users");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).isEmpty();
        assertThat(applied.denyAll()).isFalse();
    }

    @Test
    void nonMatchingDirectiveLeavesStatementUnchanged() {
        var applied = apply("SELECT * FROM ds.users",
                directive("ds.orders", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement()).isEqualTo("SELECT * FROM ds.users");
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    @Test
    void ddlIsUnaffectedEvenWhenTableIsPolicied() {
        var applied = apply("TRUNCATE TABLE ds.users",
                directive("ds.users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement()).isEqualTo("TRUNCATE TABLE ds.users");
        assertThat(applied.appliedPolicyIds()).isEmpty();
    }

    // ---- splice shapes ---------------------------------------------------------------------------

    @Test
    void insertsWhereClauseWhenAbsent() {
        var applied = apply("SELECT * FROM ds.users",
                directive("ds.users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM ds.users WHERE (`tenant` = ?)");
        assertThat(applied.parameters()).containsExactly("acme");
    }

    @Test
    void andsIntoExistingWhereClauseParenthesized() {
        var applied = apply("SELECT * FROM ds.users WHERE active = true",
                directive("users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM ds.users WHERE (active = true) AND (`tenant` = ?)");
    }

    @Test
    void spliceLandsBeforeTailClauses() {
        var applied = apply("SELECT tenant, COUNT(*) FROM ds.users GROUP BY tenant ORDER BY tenant LIMIT 5",
                directive("users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement()).isEqualTo(
                "SELECT tenant, COUNT(*) FROM ds.users WHERE (`tenant` = ?) "
                        + "GROUP BY tenant ORDER BY tenant LIMIT 5");
    }

    @Test
    void existingWhereWithTailClauseKeepsTail() {
        var applied = apply("SELECT * FROM ds.users WHERE active = true LIMIT 5",
                directive("users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement()).isEqualTo(
                "SELECT * FROM ds.users WHERE (active = true) AND (`tenant` = ?) LIMIT 5");
    }

    @Test
    void rewritesUpdateAndDelete() {
        var update = apply("UPDATE ds.users SET name = 'x' WHERE id = 7",
                directive("users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(update.statement())
                .isEqualTo("UPDATE ds.users SET name = 'x' WHERE (id = 7) AND (`tenant` = ?)");
        var delete = apply("DELETE FROM ds.users WHERE id = 7",
                directive("users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(delete.statement())
                .isEqualTo("DELETE FROM ds.users WHERE (id = 7) AND (`tenant` = ?)");
    }

    // ---- operators -------------------------------------------------------------------------------

    @Test
    void supportsAllComparisonOperators() {
        var applied = apply("SELECT * FROM ds.t",
                directive("t", "a", RowSecurityOperator.NOT_EQUALS, "x"),
                directive("t", "b", RowSecurityOperator.LESS_THAN, 1),
                directive("t", "c", RowSecurityOperator.LESS_THAN_OR_EQUAL, 2),
                directive("t", "d", RowSecurityOperator.GREATER_THAN, 3),
                directive("t", "e", RowSecurityOperator.GREATER_THAN_OR_EQUAL, 4));
        assertThat(applied.statement()).isEqualTo(
                "SELECT * FROM ds.t WHERE (`a` <> ? AND `b` < ? AND `c` <= ? AND `d` > ? AND `e` >= ?)");
        assertThat(applied.parameters()).containsExactly("x", 1, 2, 3, 4);
    }

    @Test
    void inAndNotInUseParenthesizedPlaceholderLists() {
        var applied = apply("SELECT * FROM ds.t",
                directive("t", "tenant", RowSecurityOperator.IN, "a", "b"),
                directive("t", "region", RowSecurityOperator.NOT_IN, "x"));
        assertThat(applied.statement()).isEqualTo(
                "SELECT * FROM ds.t WHERE (`tenant` IN (?, ?) AND NOT (`region` IN (?)))");
        assertThat(applied.parameters()).containsExactly("a", "b", "x");
    }

    @Test
    void isNullBindsNoParameter() {
        var applied = apply("SELECT * FROM ds.t",
                directive("t", "deleted_at", RowSecurityOperator.IS_NULL));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM ds.t WHERE (`deleted_at` IS NULL)");
        assertThat(applied.parameters()).isEmpty();
    }

    @Test
    void dottedColumnPathIsBacktickedPerSegment() {
        var applied = apply("SELECT * FROM ds.t",
                directive("t", "meta.tenant`x", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM ds.t WHERE (`meta`.`tenantx` = ?)");
    }

    // ---- deny-all --------------------------------------------------------------------------------

    @Test
    void emptyValueListIsDenyAll() {
        var directive = directive("users", "tenant", RowSecurityOperator.IN);
        var applied = apply("SELECT * FROM ds.users", directive);
        assertThat(applied.denyAll()).isTrue();
        assertThat(applied.statement()).isEqualTo("SELECT * FROM ds.users");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    // ---- fail-closed shapes ----------------------------------------------------------------------

    @Test
    void insertIntoPoliciedTableIsRejected() {
        assertThatThrownBy(() -> apply("INSERT INTO ds.users (id) VALUES (1)",
                directive("users", "tenant", RowSecurityOperator.EQUALS, "acme")))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("insert_unsupported")
                .hasMessageContaining("ds.users");
    }

    @Test
    void failsClosedOnUnrewritableShapes() {
        var tenant = directive("users", "tenant", RowSecurityOperator.EQUALS, "acme");
        for (var sql : new String[]{
                "WITH x AS (SELECT 1) SELECT * FROM ds.users",
                "SELECT * FROM ds.users WHERE id IN (SELECT id FROM ds.users)",
                "SELECT * FROM ds.users u JOIN ds.orders o ON u.id = o.user_id",
                "SELECT * FROM ds.users, ds.orders",
                "SELECT * FROM ds.users UNION ALL SELECT * FROM ds.users",
                "SELECT * FROM ds.users QUALIFY id IN (SELECT id FROM ds.users)",
                "MERGE INTO ds.users t USING ds.stage s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.v = s.v"}) {
            assertThatThrownBy(() -> apply(sql, tenant))
                    .as(sql)
                    .isInstanceOf(UnrewritableRowSecurityException.class)
                    .hasMessageContaining("unrewritable");
        }
    }

    @Test
    void failsClosedWhenDirectiveMatchesSecondaryTable() {
        // The directive targets the JOINed table, not the FROM target — still fail closed.
        assertThatThrownBy(() -> apply(
                "SELECT * FROM ds.users u JOIN ds.orders o ON u.id = o.user_id",
                directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void matchesByLastSegmentCaseInsensitively() {
        var applied = apply("SELECT * FROM `My-Project.DS.Users`",
                directive("users", "tenant", RowSecurityOperator.EQUALS, "acme"));
        assertThat(applied.statement()).endsWith("WHERE (`tenant` = ?)");
        var qualified = apply("SELECT * FROM ds.users",
                directive("other_project.other_ds.USERS", "tenant",
                        RowSecurityOperator.EQUALS, "acme"));
        assertThat(qualified.statement()).endsWith("WHERE (`tenant` = ?)");
    }

    @Test
    void multipleDirectivesRecordEveryPolicyId() {
        var one = directive("users", "tenant", RowSecurityOperator.EQUALS, "acme");
        var two = directive("users", "region", RowSecurityOperator.IN, "eu", "us");
        var applied = apply("SELECT * FROM ds.users", one, two);
        assertThat(applied.appliedPolicyIds())
                .containsExactlyInAnyOrder(one.policyId(), two.policyId());
        assertThat(applied.parameters()).containsExactly("acme", "eu", "us");
    }
}
