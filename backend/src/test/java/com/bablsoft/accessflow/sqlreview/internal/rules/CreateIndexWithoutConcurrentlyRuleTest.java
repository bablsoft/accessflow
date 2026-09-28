package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static org.assertj.core.api.Assertions.assertThat;

class CreateIndexWithoutConcurrentlyRuleTest {

    private final CreateIndexWithoutConcurrentlyRule rule = new CreateIndexWithoutConcurrentlyRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("create_index_without_concurrently");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.SCHEMA_CHANGE);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).containsExactly("table");
    }

    @Test
    void appliesToPostgresqlOnly() {
        assertThat(rule.appliesTo(DbType.POSTGRESQL)).isTrue();
        for (DbType other : new DbType[] {DbType.MYSQL, DbType.MARIADB, DbType.ORACLE, DbType.MSSQL, DbType.CUSTOM}) {
            assertThat(rule.appliesTo(other)).as(other.name()).isFalse();
        }
    }

    @Test
    void firesOnAPlainCreateIndex() {
        var findings = apply(rule, "CREATE INDEX ix ON \"Sales\".Orders (created_at)");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.args()).isEqualTo(Map.of("table", "sales.orders"));
            assertThat(f.lineNumber()).isEqualTo(1);
        });
        assertThat(apply(rule, "CREATE UNIQUE INDEX ix ON t (c)")).hasSize(1);
        assertThat(apply(rule, "CREATE INDEX ON t (c)")).hasSize(1);
    }

    @Test
    void ignoresConcurrentBuildsAndOtherStatements() {
        assertThat(apply(rule, "CREATE INDEX CONCURRENTLY ix ON t (c)")).isEmpty();
        assertThat(apply(rule,
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ix ON s.t (c) INCLUDE (d) WHERE c > 0")).isEmpty();
        assertThat(apply(rule, "CREATE TABLE t (c INT)")).isEmpty();
        assertThat(apply(rule, "DROP INDEX ix")).isEmpty();
    }
}
