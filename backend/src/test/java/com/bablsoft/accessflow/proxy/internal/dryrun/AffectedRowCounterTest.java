package com.bablsoft.accessflow.proxy.internal.dryrun;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AffectedRowCounterTest {

    @Test
    void updateWithWhereBecomesCountOverSamePredicate() {
        var count = AffectedRowCounter.toCountSql(
                "UPDATE users SET active = false WHERE last_login < '2020-01-01'");
        assertThat(count).hasValue(
                "SELECT COUNT(*) FROM users WHERE last_login < '2020-01-01'");
    }

    @Test
    void updateWithoutWhereCountsWholeTable() {
        var count = AffectedRowCounter.toCountSql("UPDATE users SET active = false");
        assertThat(count).hasValue("SELECT COUNT(*) FROM users");
    }

    @Test
    void deleteWithWhereBecomesCount() {
        var count = AffectedRowCounter.toCountSql(
            "DELETE FROM payroll.salaries WHERE year < 2019");
        assertThat(count).hasValue("SELECT COUNT(*) FROM payroll.salaries WHERE year < 2019");
    }

    @Test
    void deleteKeepsAlias() {
        var count = AffectedRowCounter.toCountSql("DELETE FROM users u WHERE u.active = false");
        assertThat(count).hasValue("SELECT COUNT(*) FROM users u WHERE u.active = false");
    }

    @Test
    void updateWithJoinIsUnsupported() {
        assertThat(AffectedRowCounter.toCountSql(
                "UPDATE orders o JOIN users u ON o.user_id = u.id SET o.flag = 1")).isEmpty();
    }

    @Test
    void updateFromIsUnsupported() {
        assertThat(AffectedRowCounter.toCountSql(
                "UPDATE orders SET flag = 1 FROM users WHERE orders.user_id = users.id")).isEmpty();
    }

    @Test
    void deleteUsingIsUnsupported() {
        assertThat(AffectedRowCounter.toCountSql(
                "DELETE FROM orders USING users WHERE orders.user_id = users.id")).isEmpty();
    }

    @Test
    void selectAndInsertAreUnsupported() {
        assertThat(AffectedRowCounter.toCountSql("SELECT * FROM users")).isEmpty();
        assertThat(AffectedRowCounter.toCountSql(
                "INSERT INTO users (id) VALUES (1)")).isEmpty();
    }

    @Test
    void unparseableSqlIsUnsupported() {
        assertThat(AffectedRowCounter.toCountSql("DELETE FROM WHERE nope !!")).isEmpty();
    }
}
