package com.bablsoft.accessflow.core.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSqlCanonicalizerTest {

    private final DefaultSqlCanonicalizer canonicalizer = new DefaultSqlCanonicalizer();

    @Test
    void nullInputReturnsNull() {
        assertThat(canonicalizer.canonicalize(null)).isNull();
    }

    @Test
    void blankInputReturnsNull() {
        assertThat(canonicalizer.canonicalize("   \n\t  ")).isNull();
    }

    @Test
    void inputWithOnlyCommentsReturnsNull() {
        assertThat(canonicalizer.canonicalize("/* nothing here */  -- empty\n")).isNull();
    }

    @Test
    void stripsBlockComments() {
        var canonical = canonicalizer.canonicalize("SELECT /* pick */ id FROM users");
        assertThat(canonical).isEqualTo("SELECT ID FROM USERS");
    }

    @Test
    void stripsMultiLineBlockComments() {
        var canonical = canonicalizer.canonicalize("""
                SELECT id
                /* multi
                   line
                   comment */
                FROM users
                """);
        assertThat(canonical).isEqualTo("SELECT ID FROM USERS");
    }

    @Test
    void stripsLineComments() {
        var canonical = canonicalizer.canonicalize("SELECT id FROM users -- only active\n");
        assertThat(canonical).isEqualTo("SELECT ID FROM USERS");
    }

    @Test
    void collapsesWhitespaceRuns() {
        var canonical = canonicalizer.canonicalize("SELECT\t id  ,\n  name\nFROM users");
        assertThat(canonical).isEqualTo("SELECT ID , NAME FROM USERS");
    }

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        var canonical = canonicalizer.canonicalize("   SELECT 1   ");
        assertThat(canonical).isEqualTo("SELECT 1");
    }

    @Test
    void upperCasesMixedCaseInput() {
        var canonical = canonicalizer.canonicalize("select Id from Users where Name = 'Ann'");
        assertThat(canonical).isEqualTo("SELECT ID FROM USERS WHERE NAME = 'ANN'");
    }

    @Test
    void treatsWhitespaceAndCommentVariationsAsEqual() {
        var a = canonicalizer.canonicalize("SELECT id FROM users WHERE active = true");
        var b = canonicalizer.canonicalize("""
                -- pick active users
                select  id
                from    users
                /* eligibility */
                where   active = TRUE
                """);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void isIdempotent() {
        var once = canonicalizer.canonicalize("/* x */ select  1");
        var twice = canonicalizer.canonicalize(once);
        assertThat(twice).isEqualTo(once);
    }
}
