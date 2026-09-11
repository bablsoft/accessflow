package com.bablsoft.accessflow.sqlreview.internal.rules;

import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

class StatementKindsTest {

    @Test
    void isDdlMatchesTheFourDdlPackages() {
        assertThat(StatementKinds.isDdl(parse("CREATE TABLE t (id INT)"))).isTrue();
        assertThat(StatementKinds.isDdl(parse("CREATE INDEX ix ON t (id)"))).isTrue();
        assertThat(StatementKinds.isDdl(parse("ALTER TABLE t ADD COLUMN c INT"))).isTrue();
        assertThat(StatementKinds.isDdl(parse("DROP TABLE t"))).isTrue();
        assertThat(StatementKinds.isDdl(parse("TRUNCATE TABLE t"))).isTrue();
        assertThat(StatementKinds.isDdl(parse("SELECT 1"))).isFalse();
        assertThat(StatementKinds.isDdl(parse("DELETE FROM t"))).isFalse();
    }

    @Test
    void isDmlCoversInsertUpdateDeleteOnly() {
        assertThat(StatementKinds.isDml(parse("INSERT INTO t VALUES (1)"))).isTrue();
        assertThat(StatementKinds.isDml(parse("UPDATE t SET a = 1"))).isTrue();
        assertThat(StatementKinds.isDml(parse("DELETE FROM t"))).isTrue();
        assertThat(StatementKinds.isDml(parse("SELECT 1"))).isFalse();
        assertThat(StatementKinds.isDml(parse("TRUNCATE TABLE t"))).isFalse();
    }

    @Test
    void typeNameIsTheUpperCasedClassName() {
        assertThat(StatementKinds.typeName(parse("DROP TABLE t"))).isEqualTo("DROP");
        assertThat(StatementKinds.typeName(parse("CREATE TABLE t (id INT)"))).isEqualTo("CREATETABLE");
    }
}
