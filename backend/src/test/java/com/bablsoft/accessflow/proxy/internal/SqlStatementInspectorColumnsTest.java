package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.ColumnReference;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementInspectorColumnsTest {

    @Test
    void selectItemResolvesToItsTable() {
        assertThat(columns("SELECT national_id FROM customer"))
                .containsExactly(ref("national_id", "customer"));
    }

    @Test
    void schemaQualifiedTableAndQuotedIdentifiersAreNormalized() {
        assertThat(columns("SELECT \"National_ID\" FROM \"Public\".customer"))
                .containsExactly(ref("national_id", "public.customer"));
    }

    @Test
    void whereGroupByHavingAndOrderByColumnsAreRecorded() {
        assertThat(columns("SELECT count(*) FROM customer WHERE a = 1 GROUP BY b "
                + "HAVING max(c) > 1 ORDER BY d"))
                .containsExactlyInAnyOrder(ref("a", "customer"), ref("b", "customer"),
                        ref("c", "customer"), ref("d", "customer"));
    }

    @Test
    void countStarIsNotAWildcard() {
        assertThat(columns("SELECT count(*) FROM customer")).isEmpty();
    }

    @Test
    void aliasQualifiedColumnsResolveThroughTheFromScope() {
        assertThat(columns("SELECT c.ssn, o.total FROM public.customer c "
                + "JOIN orders o ON o.customer_id = c.id"))
                .containsExactlyInAnyOrder(ref("ssn", "public.customer"), ref("total", "orders"),
                        ref("customer_id", "orders"), ref("id", "public.customer"));
    }

    @Test
    void unqualifiedColumnInAJoinCarriesEveryCandidateTable() {
        assertThat(columns("SELECT id FROM a JOIN b ON a.x = b.y"))
                .contains(new ColumnReference(Set.of("a", "b"), "id"));
    }

    @Test
    void joinUsingColumnsAreRecorded() {
        assertThat(columns("SELECT a.x FROM a JOIN b USING (ssn)"))
                .contains(new ColumnReference(Set.of("a", "b"), "ssn"));
    }

    @Test
    void starIsAWildcardOverTheRealTablesOfItsScope() {
        assertThat(columns("SELECT * FROM customer c JOIN orders o ON o.cid = c.id"))
                .contains(ColumnReference.wildcard(Set.of("customer", "orders")));
    }

    @Test
    void tableStarIsAWildcardOverOneTable() {
        assertThat(columns("SELECT c.* FROM customer c JOIN orders o ON o.cid = c.id"))
                .contains(ColumnReference.wildcard(Set.of("customer")))
                .doesNotContain(ColumnReference.wildcard(Set.of("customer", "orders")));
    }

    @Test
    void derivedTableColumnsAreRecordedAtTheSourceNotThroughTheAlias() {
        var refs = columns("SELECT t.x FROM (SELECT ssn AS x FROM customer) t");

        assertThat(refs).containsExactly(ref("ssn", "customer"));
    }

    @Test
    void wildcardInsideDerivedTableReachesTheRealTable() {
        assertThat(columns("SELECT t.x FROM (SELECT * FROM customer) t"))
                .contains(ColumnReference.wildcard(Set.of("customer")));
    }

    @Test
    void cteReferencesAreSkippedButTheBodyIsRecorded() {
        var refs = columns("WITH c AS (SELECT ssn FROM customer) SELECT c.ssn, * FROM c");

        assertThat(refs).contains(ref("ssn", "customer"));
        assertThat(refs).noneMatch(r -> r.candidateTables().contains("c"));
    }

    @Test
    void correlatedSubqueryColumnsIncludeOuterCandidates() {
        var refs = columns("SELECT a.id FROM a WHERE EXISTS (SELECT 1 FROM b WHERE ssn = a.id)");

        assertThat(refs).contains(new ColumnReference(Set.of("a", "b"), "ssn"));
    }

    @Test
    void unknownQualifierIsKeptAsATableName() {
        assertThat(columns("SELECT secret.ssn FROM customer"))
                .contains(ref("ssn", "secret"));
    }

    @Test
    void insertColumnListIsRecordedAgainstTheTarget() {
        assertThat(columns("INSERT INTO customer (name, ssn) VALUES ('a', 'b')"))
                .containsExactlyInAnyOrder(ref("name", "customer"), ref("ssn", "customer"));
    }

    @Test
    void insertWithoutColumnListIsAWildcardOnTheTarget() {
        assertThat(columns("INSERT INTO customer VALUES ('a', 'b')"))
                .containsExactly(ColumnReference.wildcard(Set.of("customer")));
    }

    @Test
    void insertSelectRecordsTheSourceColumns() {
        assertThat(columns("INSERT INTO archive (x) SELECT ssn FROM customer"))
                .contains(ref("x", "archive"))
                .anyMatch(r -> r.column().equals("ssn") && r.candidateTables().contains("customer"));
    }

    @Test
    void updateSetTargetsAndWhereAreRecorded() {
        assertThat(columns("UPDATE customer c SET ssn = NULL WHERE c.id = 1"))
                .containsExactlyInAnyOrder(ref("ssn", "customer"), ref("id", "customer"));
    }

    @Test
    void deleteWhereColumnsAreRecordedButDeleteIsNotAWildcard() {
        assertThat(columns("DELETE FROM customer WHERE ssn = 'x'"))
                .containsExactly(ref("ssn", "customer"));
    }

    @Test
    void returningStarIsAWildcardOnTheTarget() {
        assertThat(columns("DELETE FROM customer WHERE id = 1 RETURNING *"))
                .contains(ColumnReference.wildcard(Set.of("customer")));
    }

    @Test
    void subqueryInsideFunctionArgumentStillReportsItsStar() {
        assertThat(columns("SELECT coalesce((SELECT * FROM customer LIMIT 1), 0) FROM dual"))
                .contains(ColumnReference.wildcard(Set.of("customer")));
    }

    @Test
    void selectWithoutFromRecordsNoTable() {
        assertThat(columns("SELECT 1")).isEmpty();
    }

    private static ColumnReference ref(String column, String table) {
        return new ColumnReference(Set.of(table), column);
    }

    private static Set<ColumnReference> columns(String sql) {
        try {
            return SqlStatementInspector.inspect(CCJSqlParserUtil.parse(sql)).columns();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
