package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.QueryShape;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static com.bablsoft.accessflow.core.api.QueryShape.AGGREGATE;
import static com.bablsoft.accessflow.core.api.QueryShape.CTE;
import static com.bablsoft.accessflow.core.api.QueryShape.GROUP_BY;
import static com.bablsoft.accessflow.core.api.QueryShape.HAVING;
import static com.bablsoft.accessflow.core.api.QueryShape.JOIN;
import static com.bablsoft.accessflow.core.api.QueryShape.SUBQUERY;
import static com.bablsoft.accessflow.core.api.QueryShape.UNION;
import static com.bablsoft.accessflow.core.api.QueryShape.WINDOW_FUNCTION;
import static org.assertj.core.api.Assertions.assertThat;

class QueryShapeDetectorTest {

    private static Set<QueryShape> shapes(String sql) throws JSQLParserException {
        return QueryShapeDetector.detect(CCJSqlParserUtil.parse(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id, name FROM users WHERE id = 1 ORDER BY name",
            "SELECT * FROM users LIMIT 10",
            "INSERT INTO users (id) VALUES (1)",
            "UPDATE users SET name = 'x' WHERE id = 1",
            "DELETE FROM users WHERE id = 1",
            "SELECT upper(name), coalesce(email, '') FROM users",
            "(SELECT id FROM users)"
    })
    void aSimpleQueryHasNoShape(String sql) throws JSQLParserException {
        assertThat(shapes(sql)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM a JOIN b ON a.id = b.a_id",
            "SELECT * FROM a LEFT OUTER JOIN b ON a.id = b.a_id",
            "SELECT * FROM a, b WHERE a.id = b.a_id",
            "SELECT * FROM a CROSS JOIN b",
            "UPDATE a SET x = b.x FROM b WHERE a.id = b.id",
            "DELETE FROM a USING b WHERE a.id = b.id"
    })
    void detectsJoins(String sql) throws JSQLParserException {
        assertThat(shapes(sql)).containsExactly(JOIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id FROM a UNION SELECT id FROM b",
            "SELECT id FROM a UNION ALL SELECT id FROM b",
            "SELECT id FROM a INTERSECT SELECT id FROM b",
            "SELECT id FROM a EXCEPT SELECT id FROM b",
            "(SELECT id FROM a) UNION (SELECT id FROM b)"
    })
    void detectsSetOperationsWithoutCountingBranchesAsSubqueries(String sql) throws JSQLParserException {
        assertThat(shapes(sql)).containsExactly(UNION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)",
            "SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)",
            "SELECT (SELECT 1) AS one FROM users",
            "SELECT * FROM (SELECT id FROM users) t",
            "SELECT * FROM users WHERE id = ANY (SELECT user_id FROM orders)",
            "UPDATE users SET name = 'x' WHERE id IN (SELECT user_id FROM orders)",
            "DELETE FROM users WHERE id IN (SELECT user_id FROM orders)"
    })
    void detectsSubqueries(String sql) throws JSQLParserException {
        assertThat(shapes(sql)).containsExactly(SUBQUERY);
    }

    @Test
    void detectsLateralSubqueryAsJoinAndSubquery() throws JSQLParserException {
        assertThat(shapes("SELECT * FROM users u, LATERAL (SELECT 1 FROM orders o WHERE o.user_id = u.id) x"))
                .contains(SUBQUERY, JOIN);
    }

    @Test
    void aCteBodyIsNotASubquery() throws JSQLParserException {
        assertThat(shapes("WITH t AS (SELECT id FROM users) SELECT * FROM t")).containsExactly(CTE);
    }

    @Test
    void detectsCtesOnDataModifyingStatements() throws JSQLParserException {
        assertThat(shapes("WITH t AS (SELECT id FROM users) DELETE FROM orders WHERE user_id IN (SELECT id FROM t)"))
                .containsExactlyInAnyOrder(CTE, SUBQUERY);
    }

    @Test
    void theRowSourceOfAnInsertIsNotASubquery() throws JSQLParserException {
        assertThat(shapes("INSERT INTO archive SELECT * FROM users")).isEmpty();
        assertThat(shapes("INSERT INTO archive SELECT u.* FROM users u JOIN orders o ON o.user_id = u.id"))
                .containsExactly(JOIN);
    }

    @Test
    void detectsGroupByHavingAndAggregates() throws JSQLParserException {
        assertThat(shapes("SELECT status, count(*) FROM orders GROUP BY status HAVING count(*) > 1"))
                .containsExactlyInAnyOrder(GROUP_BY, HAVING, AGGREGATE);
        assertThat(shapes("SELECT status FROM orders GROUP BY status")).containsExactly(GROUP_BY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) FROM users",
            "SELECT sum(amount) FROM orders",
            "SELECT AVG(amount), MIN(amount), MAX(amount) FROM orders",
            "SELECT string_agg(name, ',') FROM users",
            "SELECT pg_catalog.count(*) FROM users",
            "SELECT count(*) FILTER (WHERE active) FROM users",
            "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY amount) FROM orders"
    })
    void detectsAggregates(String sql) throws JSQLParserException {
        assertThat(shapes(sql)).containsExactly(AGGREGATE);
    }

    @Test
    void aUserDefinedFunctionIsNotAnAggregate() throws JSQLParserException {
        assertThat(shapes("SELECT my_rollup(amount) FROM orders")).isEmpty();
    }

    @Test
    void detectsWindowFunctions() throws JSQLParserException {
        assertThat(shapes("SELECT row_number() OVER (ORDER BY id) FROM users"))
                .containsExactly(WINDOW_FUNCTION);
        assertThat(shapes("SELECT count(*) OVER (PARTITION BY status) FROM orders"))
                .containsExactlyInAnyOrder(WINDOW_FUNCTION, AGGREGATE);
    }

    @Test
    void detectsShapesInsideSubqueries() throws JSQLParserException {
        assertThat(shapes("SELECT * FROM users WHERE id IN (SELECT o.user_id FROM orders o JOIN items i ON i.order_id = o.id GROUP BY o.user_id)"))
                .containsExactlyInAnyOrder(SUBQUERY, JOIN, GROUP_BY);
    }

    @Test
    void theQueryOfACreateTableAsSelectIsNotASubquery() throws JSQLParserException {
        assertThat(shapes("CREATE TABLE t AS SELECT a.id FROM a JOIN b ON a.id = b.id"))
                .containsExactly(JOIN);
    }

    @Test
    void isAggregateHandlesQuotesQualifiersAndNull() {
        assertThat(QueryShapeDetector.isAggregate("\"COUNT\"")).isTrue();
        assertThat(QueryShapeDetector.isAggregate("dbo.SUM")).isTrue();
        assertThat(QueryShapeDetector.isAggregate("lower")).isFalse();
        assertThat(QueryShapeDetector.isAggregate(null)).isFalse();
    }
}
