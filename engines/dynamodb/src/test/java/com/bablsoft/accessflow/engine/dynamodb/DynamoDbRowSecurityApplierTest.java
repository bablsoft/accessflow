package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbRowSecurityApplierTest {

    private final PartiQlQueryParser parser = new PartiQlQueryParser(TestMessages.keyEcho());
    private final DynamoDbRowSecurityApplier applier =
            new DynamoDbRowSecurityApplier(TestMessages.keyEcho());

    private static RowSecurityDirective directive(String table, String column,
                                                  RowSecurityOperator op, Object... values) {
        return new RowSecurityDirective(UUID.randomUUID(), table, column, op, List.of(values));
    }

    @Test
    void leavesStatementUnchangedWhenNoDirectiveMatches() {
        var statement = parser.parseStatement("SELECT * FROM \"Music\"");
        var applied = applier.apply(statement,
                List.of(directive("Other", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement()).isEqualTo("SELECT * FROM \"Music\"");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.denyAll()).isFalse();
    }

    @Test
    void addsWhereWhenAbsentAndBindsPositionalParameter() {
        var statement = parser.parseStatement("SELECT * FROM \"Music\"");
        var applied = applier.apply(statement,
                List.of(directive("Music", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement()).isEqualTo("SELECT * FROM \"Music\" WHERE (\"tenant\" = ?)");
        assertThat(applied.parameters()).containsExactly("acme");
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void isNullOperatorSplicesIsMissingWithoutParameter() {
        var statement = parser.parseStatement("SELECT * FROM \"Music\"");
        var applied = applier.apply(statement,
                List.of(directive("Music", "deleted_at", RowSecurityOperator.IS_NULL)));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM \"Music\" WHERE (\"deleted_at\" IS MISSING)");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.denyAll()).isFalse();
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void andsIntoExistingWhereClause() {
        var statement = parser.parseStatement("SELECT * FROM \"Music\" WHERE g = 'rock'");
        var applied = applier.apply(statement,
                List.of(directive("Music", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM \"Music\" WHERE (g = 'rock') AND (\"tenant\" = ?)");
        assertThat(applied.parameters()).containsExactly("acme");
    }

    @Test
    void splicesBeforeOrderByTail() {
        var statement = parser.parseStatement("SELECT * FROM \"Music\" ORDER BY plays");
        var applied = applier.apply(statement,
                List.of(directive("Music", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM \"Music\" WHERE (\"tenant\" = ?) ORDER BY plays");
    }

    @Test
    void expandsInListAndNegatesNotIn() {
        var in = applier.apply(parser.parseStatement("SELECT * FROM \"Music\""),
                List.of(directive("Music", "tenant", RowSecurityOperator.IN, "a", "b")));
        assertThat(in.statement()).isEqualTo("SELECT * FROM \"Music\" WHERE (\"tenant\" IN [?, ?])");
        assertThat(in.parameters()).containsExactly("a", "b");

        var notIn = applier.apply(parser.parseStatement("SELECT * FROM \"Music\""),
                List.of(directive("Music", "tenant", RowSecurityOperator.NOT_IN, "a")));
        assertThat(notIn.statement()).isEqualTo("SELECT * FROM \"Music\" WHERE (NOT (\"tenant\" IN [?]))");
        assertThat(notIn.parameters()).containsExactly("a");
    }

    @Test
    void rendersComparisonOperators() {
        var applied = applier.apply(parser.parseStatement("UPDATE \"Music\" SET x = 1 WHERE \"id\" = '1'"),
                List.of(directive("Music", "plays", RowSecurityOperator.GREATER_THAN_OR_EQUAL, 10)));
        assertThat(applied.statement()).contains("\"plays\" >= ?");
        assertThat(applied.parameters()).containsExactly(10);
    }

    @Test
    void emptyValuesAreFailClosedDenyAll() {
        var applied = applier.apply(parser.parseStatement("SELECT * FROM \"Music\""),
                List.of(new RowSecurityDirective(UUID.randomUUID(), "Music", "tenant",
                        RowSecurityOperator.EQUALS, List.of())));
        assertThat(applied.denyAll()).isTrue();
        assertThat(applied.statement()).isEqualTo("SELECT * FROM \"Music\"");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void rejectsInsertIntoPolicedTable() {
        var statement = parser.parseStatement("INSERT INTO \"Music\" VALUE {'id': '1'}");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("Music", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("insert_unsupported");
    }

    @Test
    void matchesTableRefCaseInsensitivelyAndByLastSegment() {
        var applied = applier.apply(parser.parseStatement("SELECT * FROM \"Music\""),
                List.of(directive("public.music", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.parameters()).containsExactly("acme");
    }
}
