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
    void unknownQualifierTakesItselfAndEveryTableInScope() {
        assertThat(columns("SELECT secret.ssn FROM customer"))
                .contains(new ColumnReference(Set.of("secret", "customer"), "ssn"));
    }

    @Test
    void sqlServerOutputPseudoTableReachesTheTarget() {
        assertThat(columns("UPDATE users SET name = 'x' OUTPUT inserted.ssn WHERE id = 1"))
                .contains(new ColumnReference(Set.of("inserted", "users"), "ssn"));
    }

    @Test
    void postgresOnConflictExcludedReachesTheTarget() {
        assertThat(columns("INSERT INTO users (id, ssn) VALUES (1, 'x') "
                + "ON CONFLICT (id) DO UPDATE SET ssn = excluded.ssn"))
                .contains(new ColumnReference(Set.of("excluded", "users"), "ssn"));
    }

    @Test
    void bareAliasOrTableNameUsedAsAValueIsAWholeRowRead() {
        for (var sql : java.util.List.of("SELECT u FROM users u",
                "SELECT row_to_json(u) FROM users u", "SELECT to_jsonb(users) FROM users",
                "SELECT (u).ssn FROM users u", "SELECT json_agg(u) FROM users u")) {
            assertThat(columns(sql)).as(sql).contains(ColumnReference.wildcard(Set.of("users")));
        }
    }

    @Test
    void postgresFunctionalNotationOnARowIsAWholeRowRead() {
        for (var sql : java.util.List.of("SELECT u.row_to_json FROM users u",
                "SELECT users.to_jsonb FROM users", "SELECT public.users.to_json FROM public.users",
                "SELECT u.concat FROM users u", "SELECT u.hash_record FROM users u",
                "SELECT u.quote_literal FROM users u", "SELECT u.max FROM users u")) {
            assertThat(columns(sql)).as(sql)
                    .anyMatch(r -> r.isWildcard() && r.candidateTables().stream()
                            .anyMatch(t -> t.endsWith("users")));
        }
        assertThat(columns("SELECT u.name FROM users u")).noneMatch(ColumnReference::isWildcard);
    }

    @Test
    void joinsNestedInParenthesesRecordTheirNaturalAndUsingComparisons() {
        assertThat(columns("SELECT id FROM (users NATURAL JOIN orders)"))
                .contains(ColumnReference.wildcard(Set.of("users", "orders")));
        assertThat(columns("SELECT id FROM (users JOIN orders USING (ssn))"))
                .contains(new ColumnReference(Set.of("users", "orders"), "ssn"));
        assertThat(columns("SELECT o.id FROM o JOIN (users NATURAL JOIN orders) ON true"))
                .contains(ColumnReference.wildcard(Set.of("users", "orders")));
    }

    @Test
    void postgresAbsoluteValueOperatorIsAColumnReference() {
        assertThat(columns("SELECT @salary FROM emp")).contains(ref("salary", "emp"));
        assertThat(columns("SELECT @e.salary FROM emp e")).contains(ref("salary", "emp"));
        assertThat(columns("SELECT @@version")).isEmpty();
    }

    @Test
    void mysqlMultiTableUpdateJoinsAreRecorded() {
        assertThat(columns("UPDATE users u JOIN o USING (ssn) SET u.id = 1"))
                .contains(new ColumnReference(Set.of("users", "o"), "ssn"));
        assertThat(columns("UPDATE users u NATURAL JOIN o SET u.id = 1"))
                .contains(ColumnReference.wildcard(Set.of("users", "o")));
    }

    @Test
    void naturalJoinIsAWildcardOverTheJoinedTables() {
        assertThat(columns("SELECT id FROM users NATURAL JOIN orders"))
                .contains(ColumnReference.wildcard(Set.of("users", "orders")));
    }

    @Test
    void aStarInsideAnyFunctionButCountReadsEveryColumn() {
        assertThat(columns("SELECT CHECKSUM(*) FROM users"))
                .contains(ColumnReference.wildcard(Set.of("users")));
        assertThat(columns("SELECT COUNT_BIG(*) FROM users")).isEmpty();
        assertThat(columns("SELECT COUNT(CASE WHEN CHECKSUM(*) = 5 THEN 1 END) FROM users"))
                .contains(ColumnReference.wildcard(Set.of("users")));
    }

    @Test
    void aColumnNamedLikeADerivedTableIsNotAWholeRowRead() {
        assertThat(columns("SELECT t FROM (SELECT id FROM users) t"))
                .noneMatch(ColumnReference::isWildcard);
    }

    @Test
    void aliasColumnListIsAWildcardOverItsTable() {
        assertThat(columns("SELECT a FROM users AS u(a, b)"))
                .contains(ColumnReference.wildcard(Set.of("users")));
    }

    @Test
    void tableStatementIsAWildcardOverItsTable() {
        assertThat(columns("TABLE users")).containsExactly(ColumnReference.wildcard(Set.of("users")));
        assertThat(inspect("SELECT t.ssn FROM (TABLE users) t").misparsed()).isTrue();
        assertThat(inspect("SELECT * FROM users").misparsed()).isFalse();
    }

    @Test
    void pipeSyntaxReadsTheSourceWhole() {
        assertThat(columns("FROM users |> SELECT id"))
                .contains(ColumnReference.wildcard(Set.of("users")))
                .contains(ref("id", "users"));
    }

    @Test
    void mysqlFullTextMatchColumnsAreRecorded() {
        assertThat(columns("SELECT id FROM users WHERE MATCH (ssn) AGAINST ('x')"))
                .contains(ref("ssn", "users"));
    }

    @Test
    void createTableAsSelectRecordsTheColumnsItReads() {
        assertThat(columns("CREATE TABLE copy AS SELECT ssn FROM users"))
                .contains(ref("ssn", "users"));
        assertThat(columns("CREATE VIEW v AS SELECT * FROM users"))
                .contains(ColumnReference.wildcard(Set.of("users")));
    }

    @Test
    void setOperationsRecordBothSides() {
        assertThat(columns("SELECT id FROM a UNION SELECT ssn FROM b"))
                .contains(ref("id", "a"), ref("ssn", "b"));
    }

    @Test
    void lateralSubqueryColumnsAreRecorded() {
        assertThat(columns("SELECT x.v FROM a, LATERAL (SELECT ssn AS v FROM b WHERE b.id = a.id) x"))
                .anyMatch(r -> r.column().equals("ssn") && r.candidateTables().contains("b"));
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
        return inspect(sql).columns();
    }

    private static SqlStatementInspector.Inspection inspect(String sql) {
        try {
            return SqlStatementInspector.inspect(CCJSqlParserUtil.parse(sql));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
