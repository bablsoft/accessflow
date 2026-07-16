package com.bablsoft.accessflow.proxy.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchInsertPlannerTest {

    private static boolean[] allBatchable(int size) {
        var flags = new boolean[size];
        java.util.Arrays.fill(flags, true);
        return flags;
    }

    @Test
    void foldsConsecutiveHomogeneousInsertsIntoOneBatch() {
        var statements = List.of(
                "INSERT INTO users (id, name) VALUES (1, 'a')",
                "INSERT INTO users (id, name) VALUES (2, 'b')",
                "INSERT INTO users (id, name) VALUES (3, 'c')");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(3));

        assertThat(steps).hasSize(1);
        var batch = (BatchInsertPlanner.BatchStep) steps.get(0);
        assertThat(batch.templateSql()).isEqualTo("INSERT INTO users (id,name) VALUES (?, ?)");
        assertThat(batch.rowBinds()).containsExactly(
                List.of(1L, "a"), List.of(2L, "b"), List.of(3L, "c"));
    }

    @Test
    void singleInsertStaysOnPerStatementPath() {
        var steps = BatchInsertPlanner.plan(
                List.of("INSERT INTO users (id) VALUES (1)"), allBatchable(1));

        assertThat(steps).containsExactly(new BatchInsertPlanner.SingleStep(0));
    }

    @Test
    void differentTablesBreakTheRun() {
        var statements = List.of(
                "INSERT INTO users (id) VALUES (1)",
                "INSERT INTO orders (id) VALUES (2)",
                "INSERT INTO orders (id) VALUES (3)");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(3));

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0)).isEqualTo(new BatchInsertPlanner.SingleStep(0));
        var batch = (BatchInsertPlanner.BatchStep) steps.get(1);
        assertThat(batch.templateSql()).isEqualTo("INSERT INTO orders (id) VALUES (?)");
    }

    @Test
    void differentColumnListsBreakTheRun() {
        var statements = List.of(
                "INSERT INTO users (id, name) VALUES (1, 'a')",
                "INSERT INTO users (id) VALUES (2)");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(2));

        assertThat(steps).containsExactly(
                new BatchInsertPlanner.SingleStep(0), new BatchInsertPlanner.SingleStep(1));
    }

    @Test
    void insertWithoutColumnListBatchesByArity() {
        var statements = List.of(
                "INSERT INTO users VALUES (1, 'a')",
                "INSERT INTO users VALUES (2, 'b')");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(2));

        assertThat(steps).hasSize(1);
        var batch = (BatchInsertPlanner.BatchStep) steps.get(0);
        assertThat(batch.templateSql()).isEqualTo("INSERT INTO users VALUES (?, ?)");
    }

    @Test
    void literalTypeMatrixBindsExpectedValues() {
        var statements = List.of(
                "INSERT INTO t (a, b, c, d, e) VALUES (1, 2.5, 'x', NULL, -7)",
                "INSERT INTO t (a, b, c, d, e) VALUES (2, 3.5, 'y', NULL, -8)");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(2));

        assertThat(steps).hasSize(1);
        var batch = (BatchInsertPlanner.BatchStep) steps.get(0);
        assertThat(batch.rowBinds().get(0))
                .containsExactly(1L, 2.5d, "x", null, -7L);
        assertThat(batch.rowBinds().get(1))
                .containsExactly(2L, 3.5d, "y", null, -8L);
    }

    @Test
    void nonLiteralValuesFallBackToSingleSteps() {
        var statements = List.of(
                "INSERT INTO t (a) VALUES (now())",
                "INSERT INTO t (a) VALUES (now())");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(2));

        assertThat(steps).containsExactly(
                new BatchInsertPlanner.SingleStep(0), new BatchInsertPlanner.SingleStep(1));
    }

    @Test
    void insertSelectAndMultiRowValuesFallBackToSingleSteps() {
        var statements = List.of(
                "INSERT INTO t (a) SELECT a FROM s",
                "INSERT INTO t (a) VALUES (1), (2)");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(2));

        assertThat(steps).containsExactly(
                new BatchInsertPlanner.SingleStep(0), new BatchInsertPlanner.SingleStep(1));
    }

    @Test
    void nonInsertStatementsFallBackToSingleSteps() {
        var statements = List.of(
                "UPDATE t SET a = 1 WHERE id = 1",
                "DELETE FROM t WHERE id = 2");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(2));

        assertThat(steps).containsExactly(
                new BatchInsertPlanner.SingleStep(0), new BatchInsertPlanner.SingleStep(1));
    }

    @Test
    void nonBatchableFlagExcludesRewrittenStatements() {
        var statements = List.of(
                "INSERT INTO users (id) VALUES (1)",
                "INSERT INTO users (id) VALUES (2)");

        var steps = BatchInsertPlanner.plan(statements, new boolean[]{true, false});

        assertThat(steps).containsExactly(
                new BatchInsertPlanner.SingleStep(0), new BatchInsertPlanner.SingleStep(1));
    }

    @Test
    void runResumesAfterNonBatchableInterruption() {
        var statements = List.of(
                "INSERT INTO users (id) VALUES (1)",
                "INSERT INTO users (id) VALUES (2)",
                "UPDATE users SET name = 'x' WHERE id = 1",
                "INSERT INTO users (id) VALUES (3)",
                "INSERT INTO users (id) VALUES (4)");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(5));

        assertThat(steps).hasSize(3);
        assertThat(((BatchInsertPlanner.BatchStep) steps.get(0)).rowBinds())
                .containsExactly(List.of(1L), List.of(2L));
        assertThat(steps.get(1)).isEqualTo(new BatchInsertPlanner.SingleStep(2));
        assertThat(((BatchInsertPlanner.BatchStep) steps.get(2)).rowBinds())
                .containsExactly(List.of(3L), List.of(4L));
    }

    @Test
    void unparseableStatementFallsBackToSingleStep() {
        var statements = List.of("THIS IS NOT SQL", "INSERT INTO t (a) VALUES (1)");

        var steps = BatchInsertPlanner.plan(statements, allBatchable(2));

        assertThat(steps).containsExactly(
                new BatchInsertPlanner.SingleStep(0), new BatchInsertPlanner.SingleStep(1));
    }
}
