package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.PatternSyntaxException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SafeRegexTest {

    @Test
    void compilesWithAndWithoutCaseSensitivity() {
        assertThat(SafeRegex.find(SafeRegex.compile("dblink", false), "SELECT DBLINK()")).isFalse();
        assertThat(SafeRegex.find(SafeRegex.compile("dblink", true), "SELECT DBLINK()")).isTrue();
        assertThat(SafeRegex.find(SafeRegex.compile("b(l)i", false), "dblink")).isTrue();
    }

    @Test
    void rejectsMissingOverlongAndInvalidPatterns() {
        assertThatThrownBy(() -> SafeRegex.compile(null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeRegex.compile("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SafeRegex.compile("a".repeat(SafeRegex.MAX_PATTERN_LENGTH), false)).isNotNull();
        assertThatThrownBy(() -> SafeRegex.compile("(", false)).isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void catastrophicBacktrackingAbortsWithinTheBudgetInsteadOfHanging() {
        var input = "a".repeat(64) + "!";

        // JDK 9+ memoises the textbook (a+)+$, so it terminates on its own; these two do not.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(SafeRegex.find(SafeRegex.compile("(a+)+$", false), input)).isFalse();
            assertThatExceptionOfType(SafeRegex.RegexBudgetExceededException.class)
                    .isThrownBy(() -> SafeRegex.find(SafeRegex.compile("((a+)+)+$", false), input));
            assertThatExceptionOfType(SafeRegex.RegexBudgetExceededException.class)
                    .isThrownBy(() -> SafeRegex.find(SafeRegex.compile("(.*a){20}$", false), input));
        });
    }

    @Test
    void aRegexStackOverflowIsTranslatedIntoTheBudgetException() {
        // A repeated alternation recurses once per iteration and overflows long before the budget.
        var pattern = SafeRegex.compile("(\\w|\\s|,|=)*$", false);
        var input = "SELECT " + "a, b = c ".repeat(200_000) + "!";

        assertThatExceptionOfType(SafeRegex.RegexBudgetExceededException.class)
                .isThrownBy(() -> SafeRegex.find(pattern, input, Long.MAX_VALUE));
    }

    @Test
    void everyCharacterReadSpendsTheBudget() {
        var pattern = SafeRegex.compile("(x)y", false);

        assertThat(SafeRegex.find(pattern, "axyb", 100)).isTrue();
        assertThatExceptionOfType(SafeRegex.RegexBudgetExceededException.class)
                .isThrownBy(() -> SafeRegex.find(pattern, "aaaaaaaaaa", 3));
    }

    @Test
    void subSequencesShareTheBudget() {
        // A lookbehind reads through a sub-sequence view; the shared counter still runs out.
        var pattern = SafeRegex.compile("(?<=a{1,5})b", false);
        assertThatExceptionOfType(SafeRegex.RegexBudgetExceededException.class)
                .isThrownBy(() -> SafeRegex.find(pattern, "aaaaaaaaab", 4));
    }

    @Test
    void budgetGrowsQuadraticallyBetweenItsFloorAndCeiling() {
        assertThat(SafeRegex.budgetFor(10)).isEqualTo(SafeRegex.MIN_STEP_BUDGET);
        assertThat(SafeRegex.budgetFor(2_000)).isEqualTo(8_000_000L);
        assertThat(SafeRegex.budgetFor(100_000)).isEqualTo(SafeRegex.MAX_STEP_BUDGET);
    }

    @Test
    void aLeadingDotStarStillCompletesOnAMidSizeStatement() {
        var input = "UPDATE orders SET note = '" + "x".repeat(2_000) + "' WHERE id = 1";

        assertThat(SafeRegex.find(SafeRegex.compile(".*tenant_id", false), input)).isFalse();
    }

    @Test
    void theOverrunCarriesNoStackTrace() {
        var pattern = SafeRegex.compile("x", false);
        assertThatExceptionOfType(SafeRegex.RegexBudgetExceededException.class)
                .isThrownBy(() -> SafeRegex.find(pattern, "aaa", 1))
                .satisfies(ex -> assertThat(ex.getStackTrace()).isEmpty());
    }
}
