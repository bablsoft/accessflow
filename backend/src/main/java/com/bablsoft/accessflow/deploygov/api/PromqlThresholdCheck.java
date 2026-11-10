package com.bablsoft.accessflow.deploygov.api;

/**
 * Passes when the value of {@code query} on {@code prometheusUrl} satisfies
 * {@code value <comparator> threshold}. {@code noDataIsFailure} decides an empty result.
 */
public record PromqlThresholdCheck(String name, String prometheusUrl, String query,
                                   VerificationComparator comparator, double threshold,
                                   boolean noDataIsFailure) implements VerificationCheck {

    @Override
    public VerificationCheckKind kind() {
        return VerificationCheckKind.PROMQL_THRESHOLD;
    }
}
