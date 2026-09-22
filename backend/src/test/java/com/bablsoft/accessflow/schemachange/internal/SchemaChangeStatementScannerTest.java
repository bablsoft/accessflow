package com.bablsoft.accessflow.schemachange.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeStatementScannerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "BEGIN",
            "begin;",
            "BEGIN WORK; CREATE TABLE t (id INT); COMMIT;",
            "  \n\tBEGIN TRANSACTION",
            "START TRANSACTION",
            "start  transaction isolation level serializable",
            "-- a comment\nBEGIN",
            "/* block */ BEGIN",
            "/* one */ -- two\n  START /* three */ TRANSACTION",
    })
    void recognisesTransactionMarkers(String sql) {
        assertThat(SchemaChangeStatementScanner.startsWithTransactionMarker(sql)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE t (id INT)",
            "DO $$ BEGIN RAISE NOTICE 'x'; END $$",
            "BEGINNING",
            "START",
            "START SOMETHING ELSE",
            "-- BEGIN\nCREATE TABLE t (id INT)",
            "/* BEGIN */ ALTER TABLE t ADD c INT",
            "",
            "   ",
    })
    void otherLeadingTokensAreNotMarkers(String sql) {
        assertThat(SchemaChangeStatementScanner.startsWithTransactionMarker(sql)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE t (id INT); DROP TABLE u",
            "CREATE TABLE t (id INT);DROP TABLE u;",
            "CREATE TABLE t (id INT);\n-- trailing comment\nDROP TABLE u",
            "SELECT 1; -- x\n/* y */ SELECT 2",
            "CREATE TABLE t (c TEXT DEFAULT 'a;b'); DROP TABLE u",
    })
    void detectsASecondStatement(String sql) {
        assertThat(SchemaChangeStatementScanner.containsStatementSeparator(sql)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE t (id INT)",
            "CREATE TABLE t (id INT);",
            "CREATE TABLE t (id INT);   \n",
            "CREATE TABLE t (id INT); -- trailing comment",
            "CREATE TABLE t (id INT); /* trailing block */",
            "CREATE TABLE t (c TEXT DEFAULT 'a;b')",
            "CREATE TABLE t (c TEXT DEFAULT 'it''s; fine')",
            "CREATE TABLE \"weird;name\" (id INT)",
            "CREATE TABLE `weird;name` (id INT)",
            "-- a; b\nCREATE TABLE t (id INT)",
            "/* a; b */ CREATE TABLE t (id INT)",
            "CREATE FUNCTION f() RETURNS void AS $$ BEGIN PERFORM 1; PERFORM 2; END $$ LANGUAGE plpgsql",
            "CREATE FUNCTION f() RETURNS void AS $fn$ BEGIN PERFORM 1; END $fn$ LANGUAGE plpgsql",
            "CREATE FUNCTION f() RETURNS void AS $body_1$ SELECT 1; $body_1$ LANGUAGE sql",
            "SELECT $1; /* unterminated",
            "SELECT 'unterminated; literal",
            "SELECT $unterminated$ a; b",
            "",
    })
    void singleStatementsAreNotSeparated(String sql) {
        assertThat(SchemaChangeStatementScanner.containsStatementSeparator(sql)).isFalse();
    }
}
