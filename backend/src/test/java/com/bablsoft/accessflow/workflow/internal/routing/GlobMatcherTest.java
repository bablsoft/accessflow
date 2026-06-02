package com.bablsoft.accessflow.workflow.internal.routing;

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
    void bareWildcardMatchesEverything() {
        assertThat(GlobMatcher.matches("*", "anything.at.all")).isTrue();
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(GlobMatcher.matches("Payroll.*", "PAYROLL.SALARIES")).isTrue();
    }

    @Test
    void specialRegexCharactersAreTreatedLiterally() {
        // A dot in the glob is literal, not "any character".
        assertThat(GlobMatcher.matches("a.b", "axb")).isFalse();
        assertThat(GlobMatcher.matches("a.b", "a.b")).isTrue();
    }

    @Test
    void nullCandidateNeverMatches() {
        assertThat(GlobMatcher.matches("*", null)).isFalse();
    }
}
