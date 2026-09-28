package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnMaskerTest {

    @Test
    void nullInputStaysNullForEveryStrategy() {
        for (var strategy : MaskingStrategy.values()) {
            assertThat(ColumnMasker.apply(strategy, null, Map.of())).isNull();
        }
    }

    @Test
    void fullReplacesWholeValue() {
        assertThat(ColumnMasker.apply(MaskingStrategy.FULL, "4111111111111111", Map.of()))
                .isEqualTo("***");
    }

    @Test
    void partialKeepsLastNCharactersByDefault() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "4111111111111234", Map.of()))
                .isEqualTo("************1234");
    }

    @Test
    void partialHonoursVisibleSuffixParam() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abcdef",
                Map.of("visible_suffix", "2"))).isEqualTo("****ef");
    }

    @Test
    void partialMasksEverythingWhenValueNoLongerThanWindow() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abc",
                Map.of("visible_suffix", "4"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "1234",
                Map.of("visible_suffix", "4"))).isEqualTo("****");
    }

    @Test
    void partialFallsBackToDefaultOnInvalidParam() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abcdef",
                Map.of("visible_suffix", "not-a-number"))).isEqualTo("**cdef");
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abcdef",
                Map.of("visible_suffix", "  "))).isEqualTo("**cdef");
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        var first = ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of());
        var second = ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of());
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        // Known SHA-256 of "secret".
        assertThat(first).isEqualTo(
                "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b");
    }

    @Test
    void hashWithSaltIsDeterministicAndDiffersFromUnsalted() {
        var salted = ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of("salt", "pepper"));
        var saltedAgain = ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of("salt", "pepper"));
        var unsalted = ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of());
        assertThat(salted).isEqualTo(saltedAgain).hasSize(64).isNotEqualTo(unsalted);
        // Salt prepended: equals SHA-256 of "peppersecret".
        assertThat(salted).isEqualTo(ColumnMasker.apply(MaskingStrategy.HASH, "peppersecret", Map.of()));
    }

    @Test
    void hashWithBlankSaltFallsBackToPlain() {
        assertThat(ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of("salt", " ")))
                .isEqualTo(ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of()));
    }

    @Test
    void emailPreservesFirstCharAndDomain() {
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "jane.doe@example.com", Map.of()))
                .isEqualTo("j***@example.com");
    }

    @Test
    void emailFallsBackToFullMaskWhenNotEmailShaped() {
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "not-an-email", Map.of()))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "@nolocal.com", Map.of()))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "nodomain@", Map.of()))
                .isEqualTo("***");
    }

    @Test
    void formatPreservingKeepsShapeReplacingDigitsAndLetters() {
        assertThat(ColumnMasker.apply(MaskingStrategy.FORMAT_PRESERVING, "555-12-3456", Map.of()))
                .isEqualTo("***-**-****");
        assertThat(ColumnMasker.apply(MaskingStrategy.FORMAT_PRESERVING, "AB-12 cd", Map.of()))
                .isEqualTo("xx-** xx");
    }

    @Test
    void keepFirstKeepsLeadingCharacters() {
        assertThat(ColumnMasker.apply(MaskingStrategy.KEEP_FIRST, "0912345678",
                Map.of("visible_prefix", "4"))).isEqualTo("0912******");
        assertThat(ColumnMasker.apply(MaskingStrategy.KEEP_FIRST, "abcdef", Map.of()))
                .isEqualTo("abcd**");
    }

    @Test
    void keepFirstMasksEverythingWhenValueNoLongerThanWindow() {
        assertThat(ColumnMasker.apply(MaskingStrategy.KEEP_FIRST, "abc",
                Map.of("visible_prefix", "4"))).isEqualTo("***");
    }

    @Test
    void keepFirstFallsBackToDefaultOnInvalidParam() {
        assertThat(ColumnMasker.apply(MaskingStrategy.KEEP_FIRST, "abcdef",
                Map.of("visible_prefix", "-3"))).isEqualTo("abcd**");
    }

    @Test
    void constantReplacesEveryValue() {
        assertThat(ColumnMasker.apply(MaskingStrategy.CONSTANT, "secret",
                Map.of("replacement", "REDACTED"))).isEqualTo("REDACTED");
    }

    @Test
    void constantWithoutReplacementFallsBackToFullMask() {
        assertThat(ColumnMasker.apply(MaskingStrategy.CONSTANT, "secret", Map.of())).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.CONSTANT, "secret", null)).isEqualTo("***");
    }

    @Test
    void nullifyYieldsNull() {
        assertThat(ColumnMasker.apply(MaskingStrategy.NULLIFY, "secret", Map.of())).isNull();
    }

    @Test
    void regexReplaceUsesCaptureGroups() {
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "0912345678",
                Map.of("pattern", "^(\\d{3})\\d+(\\d{2})$", "replacement", "$1-XXXXX-$2")))
                .isEqualTo("091-XXXXX-78");
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "jane@example.com",
                Map.of("pattern", "^[^@]+(?<domain>@.*)$", "replacement", "user${domain}")))
                .isEqualTo("user@example.com");
    }

    @Test
    void regexReplaceMasksValueThePatternDoesNotMatch() {
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "no digits here",
                Map.of("pattern", "\\d+", "replacement", "#"))).isEqualTo("***");
    }

    @Test
    void regexReplaceFailsClosedOnCatastrophicBacktracking() {
        var evil = "a".repeat(40) + "!";
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, evil,
                Map.of("pattern", "^(a+)+$", "replacement", "x"))).isEqualTo("***");
    }

    @Test
    void regexReplaceFailsClosedWhenAMatchingEvaluationExceedsItsBudget() {
        // The pattern matches, so only the budget can turn this into a full mask.
        assertThat(ColumnMasker.regexReplace("aaaaaaaaaa", "a+", "b", 1_000)).isEqualTo("b");
        assertThat(ColumnMasker.regexReplace("aaaaaaaaaa", "a+", "b", 5)).isEqualTo("***");
    }

    @Test
    void regexReplaceMasksOutputThatExpandsPastTheCap() {
        var value = "a".repeat(100);
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, value,
                Map.of("pattern", "a", "replacement", "b".repeat(100)))).isEqualTo("***");
    }

    @Test
    void regexReplaceMasksValuesMatchedOnlyByEmptyStrings() {
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "secret",
                Map.of("pattern", "\\d*", "replacement", "#"))).isEqualTo("***");
    }

    @Test
    void regexReplaceFailsClosedOnInvalidPatternOrReplacement() {
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "abc",
                Map.of("pattern", "(unclosed", "replacement", "x"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "abc",
                Map.of("pattern", "a", "replacement", "$9"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "abc",
                Map.of("pattern", "a"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "abc", Map.of())).isEqualTo("***");
    }

    @Test
    void regexReplaceMasksOverlongInput() {
        var longValue = "a".repeat(ColumnMasker.MAX_REGEX_INPUT_LENGTH + 1);
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, longValue,
                Map.of("pattern", "a", "replacement", "b"))).isEqualTo("***");
    }

    @Test
    void regexReplaceReusesCachedPattern() {
        var params = Map.of("pattern", "x", "replacement", "y");
        assertThat(ColumnMasker.apply(MaskingStrategy.REGEX_REPLACE, "xx", params)).isEqualTo("yy");
        assertThat(ColumnMasker.compile("x")).isSameAs(ColumnMasker.compile("x"));
    }

    @Test
    void numericBucketFloorsToBucketSize() {
        var params = Map.of("bucket_size", "10000");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "54321.50", params))
                .isEqualTo("50000");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "-1", params))
                .isEqualTo("-10000");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "7.3",
                Map.of("bucket_size", "0.5"))).isEqualTo("7");
    }

    @Test
    void numericBucketUsesBoundaries() {
        var params = Map.of("boundaries", "18, 30, 65");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "12", params)).isEqualTo("<18");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "18", params)).isEqualTo("[18, 30)");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "42", params)).isEqualTo("[30, 65)");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "65", params)).isEqualTo(">=65");
    }

    @Test
    void numericBucketFailsClosedOnNonNumericValueOrMissingParams() {
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "n/a",
                Map.of("bucket_size", "10"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "12", Map.of())).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "12",
                Map.of("boundaries", "30,18"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "12",
                Map.of("bucket_size", "-5"))).isEqualTo("***");
    }

    @Test
    void numericBucketMasksExtremeExponentsInsteadOfComputingThem() {
        var params = Map.of("bucket_size", "10");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "1E+999999999", params))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "1E-999999999", params))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "5",
                Map.of("bucket_size", "1E-100000000"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.NUMERIC_BUCKET, "1E+999999999",
                Map.of("boundaries", "1,2"))).isEqualTo("***");
    }

    @Test
    void dateGeneralizeTruncatesToPrecision() {
        assertThat(ColumnMasker.apply(MaskingStrategy.DATE_GENERALIZE, "1987-05-17",
                Map.of("precision", "YEAR"))).isEqualTo("1987");
        assertThat(ColumnMasker.apply(MaskingStrategy.DATE_GENERALIZE, "1987-05-17 10:00:00.0",
                Map.of("precision", "quarter"))).isEqualTo("1987-Q2");
        assertThat(ColumnMasker.apply(MaskingStrategy.DATE_GENERALIZE, "1987-12-01T00:00Z",
                Map.of("precision", "MONTH"))).isEqualTo("1987-12");
    }

    @Test
    void dateGeneralizeFailsClosedOnNonDateValues() {
        var params = Map.of("precision", "YEAR");
        assertThat(ColumnMasker.apply(MaskingStrategy.DATE_GENERALIZE, "Sun May 17 1987", params))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.DATE_GENERALIZE, "1987-13-01", params))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.DATE_GENERALIZE, "1987-05-17", Map.of()))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.DATE_GENERALIZE, "1987-05-17",
                Map.of("precision", "DAY"))).isEqualTo("***");
    }

    @Test
    void readsRawValueIsFalseOnlyForValueIndependentStrategies() {
        for (var strategy : MaskingStrategy.values()) {
            var independent = strategy == MaskingStrategy.FULL || strategy == MaskingStrategy.CONSTANT
                    || strategy == MaskingStrategy.NULLIFY;
            assertThat(ColumnMasker.readsRawValue(strategy)).isEqualTo(!independent);
        }
    }

    @Test
    void applyWithoutValueMatchesApplyForValueIndependentStrategies() {
        var params = Map.of("replacement", "REDACTED");
        assertThat(ColumnMasker.applyWithoutValue(MaskingStrategy.FULL, Map.of())).isEqualTo("***");
        assertThat(ColumnMasker.applyWithoutValue(MaskingStrategy.CONSTANT, params)).isEqualTo("REDACTED");
        assertThat(ColumnMasker.applyWithoutValue(MaskingStrategy.NULLIFY, Map.of())).isNull();
        assertThat(ColumnMasker.applyWithoutValue(MaskingStrategy.PARTIAL, Map.of())).isEqualTo("***");
    }

    @Test
    void budgetedCharSequenceSharesBudgetAcrossSubSequences() {
        var sequence = new ColumnMasker.BudgetedCharSequence("abcdef", 3);
        var sub = sequence.subSequence(1, 4);
        assertThat(sub.length()).isEqualTo(3);
        assertThat(sub.toString()).isEqualTo("bcd");
        assertThat(sequence.charAt(0)).isEqualTo('a');
        assertThat(sub.charAt(0)).isEqualTo('b');
        assertThat(sequence.charAt(1)).isEqualTo('b');
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sub.charAt(1))
                .isInstanceOf(ColumnMasker.RegexBudgetExceededException.class);
    }
}
