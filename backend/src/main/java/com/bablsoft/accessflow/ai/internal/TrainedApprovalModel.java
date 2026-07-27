package com.bablsoft.accessflow.ai.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record TrainedApprovalModel(
        String featureSchemaVersion,
        List<String> featureNames,
        double intercept,
        Map<String, Double> weights,
        Map<String, Double> means,
        Map<String, Double> stddevs
) {
    public TrainedApprovalModel {
        featureNames = List.copyOf(featureNames);
        weights = Map.copyOf(weights);
        means = Map.copyOf(means);
        stddevs = Map.copyOf(stddevs);

        // Reject duplicate feature names — a duplicate collapses in the maps and
        // would cause predict() to silently double-count the surviving weight.
        var seen = new HashSet<String>();
        for (var name : featureNames) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException(
                        "Duplicate feature name: '" + name + "'");
            }
        }

        // Validate that every declared feature has a coefficient, mean, and stddev.
        // A missing entry would cause predict() to silently substitute 0/1 defaults
        // and return a plausible-looking but wrong probability.
        for (var name : featureNames) {
            if (!weights.containsKey(name)) {
                throw new IllegalArgumentException("Missing weight for feature: '" + name + "'");
            }
            if (!means.containsKey(name)) {
                throw new IllegalArgumentException("Missing mean for feature: '" + name + "'");
            }
            if (!stddevs.containsKey(name)) {
                throw new IllegalArgumentException("Missing stddev for feature: '" + name + "'");
            }
        }
    }

    public double predict(double[] rawFeatures) {
        if (rawFeatures.length != featureNames.size()) {
            throw new IllegalArgumentException(
                    "Feature length mismatch: expected " + featureNames.size()
                    + ", got " + rawFeatures.length);
        }
        double logit = intercept;
        for (int i = 0; i < rawFeatures.length; i++) {
            var fname = featureNames.get(i);
            double val = rawFeatures[i];
            double mean = means.get(fname);
            double std = stddevs.get(fname);
            double scaled = (val - mean) / std;
            double w = weights.get(fname);
            logit += w * scaled;
        }
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JacksonException e) {
            throw new ApprovalModelSerializationException(
                    "Failed to serialize approval model", e);
        }
    }

    public static TrainedApprovalModel fromJson(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, TrainedApprovalModel.class);
        } catch (JacksonException e) {
            throw new ApprovalModelSerializationException(
                    "Failed to deserialize approval model", e);
        }
    }
}
