package com.bablsoft.accessflow.ai.internal;

import tools.jackson.databind.ObjectMapper;
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
    }

    public double predict(double[] rawFeatures) {
        if (rawFeatures.length != featureNames.size()) {
            throw new IllegalArgumentException("Feature length mismatch");
        }
        double logit = intercept;
        for (int i = 0; i < rawFeatures.length; i++) {
            String fname = featureNames.get(i);
            double val = rawFeatures[i];
            double mean = means.getOrDefault(fname, 0.0);
            double std = stddevs.getOrDefault(fname, 1.0);
            double scaled = (val - mean) / std;
            double w = weights.getOrDefault(fname, 0.0);
            logit += w * scaled;
        }
        return 1.0 / (1.0 + Math.exp(-logit));
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static TrainedApprovalModel fromJson(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, TrainedApprovalModel.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
