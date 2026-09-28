package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class AddNotNullColumnWithoutDefaultRuleTest {

    private final AddNotNullColumnWithoutDefaultRule rule = new AddNotNullColumnWithoutDefaultRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("add_not_null_column_without_default");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.SCHEMA_CHANGE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).containsExactly("column");
        assertThat(rule.appliesTo(DbType.MYSQL)).isTrue();
    }

    @Test
    void firesOnNotNullWithoutDefault() {
        var findings = apply(rule, "ALTER TABLE \"Sales\".Orders ADD COLUMN Note TEXT NOT NULL");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.args()).isEqualTo(Map.of("column", "sales.orders.note"));
            assertThat(f.lineNumber()).isEqualTo(1);
        });
    }

    @Test
    void firesOnEveryDialectsAddForm() {
        assertThat(apply(rule, "ALTER TABLE t ADD c VARCHAR(10) NOT NULL")).hasSize(1);
        assertThat(apply(rule, "ALTER TABLE t ADD (c NUMBER(5) NOT NULL, d INT)")).singleElement()
                .satisfies(f -> assertThat(f.args()).containsEntry("column", "t.c"));
    }

    @Test
    void firesOncePerOffendingColumn() {
        var findings = apply(rule, "ALTER TABLE t ADD COLUMN a INT NOT NULL, ADD COLUMN b INT, ADD COLUMN c INT NOT NULL");
        assertThat(findings).extracting(f -> f.args().get("column")).containsExactly("t.a", "t.c");
    }

    @Test
    void ignoresDefaultsNullableAndGeneratedColumns() {
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c INT NOT NULL DEFAULT 0")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c INT DEFAULT 0 NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c INT")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN id BIGSERIAL NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN id BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN id INT NOT NULL AUTO_INCREMENT")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t ADD id INT IDENTITY(1,1) NOT NULL")).isEmpty();
    }

    @Test
    void generatedMarkersInsideOtherTokensDoNotExempt() {
        assertThat(apply(rule,
                "ALTER TABLE users ADD COLUMN idp_id UUID NOT NULL REFERENCES identity_providers(id)")).hasSize(1);
        assertThat(apply(rule, "ALTER TABLE t ADD COLUMN c TEXT NOT NULL COMMENT 'generated identity'"))
                .hasSize(1);
    }

    @Test
    void ignoresOtherStatements() {
        assertThat(apply(rule, "ALTER TABLE t ALTER COLUMN c SET NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t MODIFY c VARCHAR(20) NOT NULL")).isEmpty();
        assertThat(apply(rule, "ALTER TABLE t DROP COLUMN c")).isEmpty();
        assertThat(apply(rule, "CREATE TABLE t (c INT NOT NULL)")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
    }

    @Test
    void envelopeFindingsHaveNoLine() {
        var findings = rule.apply(RuleTestSupport.envelope(2, "ALTER TABLE t ADD COLUMN c INT NOT NULL"), Map.of());
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.statementIndex()).isEqualTo(2);
            assertThat(f.lineNumber()).isNull();
        });
    }
}
