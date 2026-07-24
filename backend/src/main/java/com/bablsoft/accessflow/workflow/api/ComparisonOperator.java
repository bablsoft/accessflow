package com.bablsoft.accessflow.workflow.api;

/**
 * Numeric comparison used by {@link ConditionNode.RiskScore} against the AI risk score (0–100).
 */
public enum ComparisonOperator {
    LT,
    LTE,
    GT,
    GTE,
    EQ;

    /**
     * @return {@code true} if {@code left <op> right} holds.
     */
    public boolean test(int left, int right) {
        return test((long) left, (long) right);
    }

    /**
     * @return {@code true} if {@code left <op> right} holds.
     */
    public boolean test(long left, long right) {
        return switch (this) {
            case LT -> left < right;
            case LTE -> left <= right;
            case GT -> left > right;
            case GTE -> left >= right;
            case EQ -> left == right;
        };
    }
}
