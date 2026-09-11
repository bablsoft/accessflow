package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobMatcherTest {

    @Test
    void exactMatch() {
        assertThat(GlobMatcher.matches("public.orders", "public.orders")).isTrue();
        assertThat(GlobMatcher.matches("public.orders", "public.customers")).isFalse();
    }

    @Test
    void schemaWildcardMatchesAllTablesInSchema() {
        assertThat(GlobMatcher.matches("payroll.*", "payroll.salaries")).isTrue();
        assertThat(GlobMatcher.matches("payroll.*", "payroll.bonuses")).isTrue();
        assertThat(GlobMatcher.matches("payroll.*", "hr.salaries")).isFalse();
    }

    @Test
    void leadingWildcardMatchesAnySchema() {
        assertThat(GlobMatcher.matches("*.users", "public.users")).isTrue();
        assertThat(GlobMatcher.matches("*.users", "auth.users")).isTrue();
        assertThat(GlobMatcher.matches("*.users", "public.accounts")).isFalse();
    }

    @Test
    void starMatchesAnyRunIncludingDotsAndSeparators() {
        assertThat(GlobMatcher.matches("2.*", "2.4.1")).isTrue();
        assertThat(GlobMatcher.matches("*-rc*", "3.0.0-rc2")).isTrue();
        assertThat(GlobMatcher.matches("/v1/*", "/v1/users/42")).isTrue();
        assertThat(GlobMatcher.matches("*", "anything.at.all")).isTrue();
    }

    @Test
    void literalCharactersMustMatchExactly() {
        assertThat(GlobMatcher.matches("2.*", "12.4.1")).isFalse();
        assertThat(GlobMatcher.matches("2.4.1", "2.4.1")).isTrue();
        assertThat(GlobMatcher.matches("2.4.1", "2X4X1")).isFalse();
    }

    @Test
    void matchingIsCaseInsensitiveAndTrimmed() {
        assertThat(GlobMatcher.matches("Payroll.*", "PAYROLL.SALARIES")).isTrue();
        assertThat(GlobMatcher.matches("  V2.*  ", "v2.0.0")).isTrue();
    }

    @Test
    void specialRegexCharactersAreTreatedLiterally() {
        // A dot in the glob is literal, not "any character".
        assertThat(GlobMatcher.matches("a.b", "axb")).isFalse();
        assertThat(GlobMatcher.matches("a.b", "a.b")).isTrue();
        assertThat(GlobMatcher.matches("a+b", "aab")).isFalse();
    }

    @Test
    void nullGlobOrCandidateNeverMatches() {
        assertThat(GlobMatcher.matches(null, "2.4.1")).isFalse();
        assertThat(GlobMatcher.matches("*", null)).isFalse();
    }

    @Test
    void compileProducesAnchoredCaseInsensitivePattern() {
        assertThat(GlobMatcher.compile("payroll.*").matcher("payroll.x").matches()).isTrue();
        assertThat(GlobMatcher.compile("payroll.*").matcher("xpayroll.x").matches()).isFalse();
        assertThat(GlobMatcher.compile(null).matcher("").matches()).isTrue();
    }
}
