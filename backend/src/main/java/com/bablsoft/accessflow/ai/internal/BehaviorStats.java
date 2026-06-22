package com.bablsoft.accessflow.ai.internal;

import java.util.ArrayList;
import java.util.List;

/** Small numeric helpers for the anomaly detector: mean, sample standard deviation, and percentile. */
final class BehaviorStats {

    private BehaviorStats() {
    }

    static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** Sample (n-1) standard deviation; 0 for fewer than two values. */
    static double sampleStddev(List<Double> values) {
        int n = values.size();
        if (n < 2) {
            return 0.0;
        }
        double mean = mean(values);
        double sumSq = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / (n - 1));
    }

    /** Linear-interpolation percentile ({@code p} in [0,1]) over a copy of {@code values}. */
    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double rank = p * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double weight = rank - lower;
        return sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
    }
}
