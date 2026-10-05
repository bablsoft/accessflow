package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.context;
import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.envelope;
import static org.assertj.core.api.Assertions.assertThat;

class StatementFactsTest {

    private static StatementFacts facts(String sql) {
        return context(sql).facts();
    }

    @Test
    void classifiesLikeTheProxy() {
        assertThat(facts("SELECT 1").queryType()).isEqualTo(QueryType.SELECT);
        assertThat(facts("INSERT INTO t VALUES (1)").queryType()).isEqualTo(QueryType.INSERT);
        assertThat(facts("UPDATE t SET a = 1").queryType()).isEqualTo(QueryType.UPDATE);
        assertThat(facts("DELETE FROM t").queryType()).isEqualTo(QueryType.DELETE);
        assertThat(facts("DROP TABLE t").queryType()).isEqualTo(QueryType.DDL);
        assertThat(facts("CREATE INDEX i ON t (a)").queryType()).isEqualTo(QueryType.DDL);
        assertThat(facts("GRANT SELECT ON t TO u").queryType()).isEqualTo(QueryType.OTHER);
    }

    @Test
    void collectsTablesColumnsAndFunctionsNormalised() {
        var facts = facts("SELECT o.Email, COUNT(*), PG_CATALOG.Now() FROM \"Sales\".Orders o "
                + "JOIN customers c ON c.id = o.customer_id WHERE upper(c.name) = 'X'");

        assertThat(facts.tables()).containsExactly("customers", "sales.orders");
        assertThat(facts.columns()).contains("o.email", "c.id", "o.customer_id", "c.name");
        assertThat(facts.functions()).containsExactly("count", "now", "upper");
    }

    @Test
    void collectsUpdateAssignmentColumns() {
        assertThat(facts("UPDATE users SET ssn = NULL WHERE id = 1").columns()).contains("ssn", "id");
    }

    @Test
    void derivesClauseFactsForSelect() {
        var limited = facts("SELECT a FROM t WHERE a > 1 ORDER BY a LIMIT 5");
        assertThat(limited.hasWhere()).isTrue();
        assertThat(limited.hasLimit()).isTrue();
        assertThat(limited.hasOrderBy()).isTrue();

        var bare = facts("SELECT a FROM t");
        assertThat(bare.hasWhere()).isFalse();
        assertThat(bare.hasLimit()).isFalse();
        assertThat(bare.hasOrderBy()).isFalse();
    }

    @Test
    void derivesClauseFactsForMysqlStyleUpdateAndDelete() {
        var update = facts("UPDATE t SET a = 1 ORDER BY id LIMIT 10");
        assertThat(update.hasLimit()).isTrue();
        assertThat(update.hasOrderBy()).isTrue();
        var delete = facts("DELETE FROM t ORDER BY id LIMIT 10");
        assertThat(delete.hasLimit()).isTrue();
        assertThat(delete.hasOrderBy()).isTrue();
        var plain = facts("DELETE FROM t");
        assertThat(plain.hasLimit()).isFalse();
        assertThat(plain.hasOrderBy()).isFalse();
        var insert = facts("INSERT INTO t VALUES (1)");
        assertThat(insert.hasLimit()).isFalse();
        assertThat(insert.hasOrderBy()).isFalse();
    }

    @Test
    void sharesTheBuiltInDetectors() {
        assertThat(facts("DELETE FROM t WHERE 1 = 1").whereAlwaysTrue()).isTrue();
        assertThat(facts("DELETE FROM t WHERE id = 1").whereAlwaysTrue()).isFalse();
        assertThat(facts("SELECT * FROM a CROSS JOIN b").joinWithoutCondition()).isTrue();
        assertThat(facts("SELECT * FROM a JOIN b ON a.id = b.id").joinWithoutCondition()).isFalse();
        assertThat(facts("SELECT * FROM t WHERE n LIKE '%x'").leadingWildcardLike()).isTrue();
        assertThat(facts("SELECT * FROM t WHERE n LIKE 'x%'").leadingWildcardLike()).isFalse();
    }

    @Test
    void readsTheEnvelopeFlagAndNormalisesText() {
        assertThat(envelope(0, "DELETE FROM t WHERE id = 1").facts().transactional()).isTrue();
        assertThat(facts("DELETE FROM t").transactional()).isFalse();
        assertThat(facts("SELECT  a\n\t FROM   t -- trailing\n").normalizedText()).isEqualTo("SELECT a FROM t");
    }

    @Test
    void isMemoisedPerContext() {
        var context = context("SELECT 1");
        assertThat(context.facts()).isSameAs(context.facts());
    }
}
