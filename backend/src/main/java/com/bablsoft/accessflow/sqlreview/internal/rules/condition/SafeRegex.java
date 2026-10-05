package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import java.util.regex.Pattern;

/**
 * Bounded evaluation of the {@code sql_matches} escape hatch (#1009). {@code java.util.regex} is a
 * backtracking engine, so an admin-written pattern such as {@code (a+)+$} can take exponential time
 * on a hostile statement. Two guards: a pattern-length cap at compile time, and a step budget at
 * match time — the input is wrapped in a {@link CharSequence} that counts character reads and throws
 * {@link RegexBudgetExceededException} past the budget (a regex stack overflow is translated to the
 * same exception). That exception is a
 * {@link RuntimeException}, which {@code SqlReviewEvaluator} already turns into "skip this rule for
 * this statement", so a runaway pattern never hangs a submission. The cost is that an overrun
 * fails open, like a throwing built-in: a statement padded far enough skips the rule, so a
 * {@code sql_matches} rule is a lint, not a security boundary.
 */
public final class SafeRegex {

    public static final int MAX_PATTERN_LENGTH = 500;

    /** Floor of the per-match budget, in character reads. */
    static final long MIN_STEP_BUDGET = 1_000_000L;

    /** Ceiling of the per-match budget — roughly a quarter of a second of backtracking. */
    static final long MAX_STEP_BUDGET = 50_000_000L;

    private SafeRegex() {
    }

    /**
     * Compiles {@code pattern}; throws {@link IllegalArgumentException} past
     * {@link #MAX_PATTERN_LENGTH} and {@link java.util.regex.PatternSyntaxException} when it does
     * not compile.
     */
    public static Pattern compile(String pattern, boolean ignoreCase) {
        if (pattern == null || pattern.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("Regex pattern missing or longer than " + MAX_PATTERN_LENGTH);
        }
        return Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    }

    /**
     * Whether {@code pattern} is found in {@code input}, within {@link #budgetFor} reads. The budget
     * grows with the square of the input so an ordinary quadratic pattern — a leading {@code .*}
     * retried at every start offset — still completes on a statement of a few thousand characters,
     * while an exponential one is cut off at {@link #MAX_STEP_BUDGET}.
     */
    public static boolean find(Pattern pattern, CharSequence input) {
        return find(pattern, input, budgetFor(input.length()));
    }

    static long budgetFor(int length) {
        long quadratic = 2L * length * length;
        return Math.min(MAX_STEP_BUDGET, Math.max(MIN_STEP_BUDGET, quadratic));
    }

    static boolean find(Pattern pattern, CharSequence input, long budget) {
        try {
            return pattern.matcher(new BudgetedCharSequence(input, new long[] {budget})).find();
        } catch (StackOverflowError ex) {
            // java.util.regex recurses once per iteration of a repeated alternation, so a pattern such
            // as (\w|\s)*$ overflows the stack on a long statement before the budget is spent. An
            // Error would escape SqlReviewEvaluator's RuntimeException guard and fail the submission.
            throw new RegexBudgetExceededException();
        }
    }

    /** Thrown when a match exhausts its step budget. */
    public static final class RegexBudgetExceededException extends RuntimeException {

        RegexBudgetExceededException() {
            // No stack trace: an overrun is expected control flow, logged once per rule and statement.
            super("SQL review regex exceeded its step budget", null, false, false);
        }
    }

    /** A view of {@code delegate} that spends one unit of the shared budget per character read. */
    private static final class BudgetedCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final long[] remaining;

        BudgetedCharSequence(CharSequence delegate, long[] remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining[0] < 0) {
                throw new RegexBudgetExceededException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedCharSequence(delegate.subSequence(start, end), remaining);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
