package com.bablsoft.accessflow.deploygov.internal.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobMatcherTest {

    @Test
    void starMatchesAnyRunIncludingDots() {
        assertThat(GlobMatcher.matches("2.*", "2.4.1")).isTrue();
        assertThat(GlobMatcher.matches("*-rc*", "3.0.0-rc2")).isTrue();
        assertThat(GlobMatcher.matches("*", "anything")).isTrue();
    }

    @Test
    void literalCharactersMustMatchExactly() {
        assertThat(GlobMatcher.matches("2.*", "12.4.1")).isFalse();
        assertThat(GlobMatcher.matches("2.4.1", "2.4.1")).isTrue();
        assertThat(GlobMatcher.matches("2.4.1", "2X4X1")).isFalse();
    }

    @Test
    void matchingIsCaseInsensitiveAndTrimmed() {
        assertThat(GlobMatcher.matches("  V2.*  ", "v2.0.0")).isTrue();
    }

    @Test
    void nullGlobOrCandidateNeverMatches() {
        assertThat(GlobMatcher.matches(null, "2.4.1")).isFalse();
        assertThat(GlobMatcher.matches("2.*", null)).isFalse();
    }
}
