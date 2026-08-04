package com.bablsoft.accessflow.ai.internal;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LogisticRegressionTrainer {

    public TrainedApprovalModel train(double[][] features, boolean[] labels, List<String> featureNames, double lambda, int maxIterations) {
        int n = features.length;
        if (n == 0) {
            throw new IllegalArgumentException("No training samples");
        }
        int m = featureNames.size();
        if (labels.length != n) {
            throw new IllegalArgumentException(
                    "labels.length (" + labels.length + ") must equal features.length (" + n + ")");
        }
        for (int i = 0; i < n; i++) {
            if (features[i].length != m) {
                throw new IllegalArgumentException(
                        "features[" + i + "].length (" + features[i].length
                        + ") must equal featureNames.size() (" + m + ")");
            }
        }

        double[] means = new double[m];
        double[] stddevs = new double[m];

        // Calculate means
        for (int j = 0; j < m; j++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += features[i][j];
            }
            means[j] = sum / n;
        }

        // Calculate stddevs (sample stddev)
        for (int j = 0; j < m; j++) {
            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double diff = features[i][j] - means[j];
                sumSq += diff * diff;
            }
            if (n > 1) {
                stddevs[j] = Math.sqrt(sumSq / (n - 1));
            } else {
                stddevs[j] = 0.0;
            }
            if (stddevs[j] < 1e-9) {
                stddevs[j] = 1.0;
            }
        }

        // Standardize features
        double[][] scaled = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                scaled[i][j] = (features[i][j] - means[j]) / stddevs[j];
            }
        }

        // Gradient descent
        double[] w = new double[m];
        double b = 0;
        double lr = 0.1;
        double prevLoss = Double.MAX_VALUE;

        for (int iter = 0; iter < maxIterations; iter++) {
            double[] dw = new double[m];
            double db = 0;
            double loss = 0;

            for (int i = 0; i < n; i++) {
                double logit = b;
                for (int j = 0; j < m; j++) {
                    logit += w[j] * scaled[i][j];
                }

                double p = 1.0 / (1.0 + Math.exp(-logit));
                double y = labels[i] ? 1.0 : 0.0;

                // Avoid log(0)
                double pSafe = Math.max(1e-15, Math.min(1.0 - 1e-15, p));
                loss -= (y * Math.log(pSafe) + (1.0 - y) * Math.log(1.0 - pSafe));

                double dz = p - y;
                db += dz;
                for (int j = 0; j < m; j++) {
                    dw[j] += dz * scaled[i][j];
                }
            }

            loss /= n;
            for (int j = 0; j < m; j++) {
                loss += (lambda / 2.0) * w[j] * w[j];
                dw[j] = (dw[j] / n) + (lambda * w[j]);
            }
            db /= n;

            if (prevLoss - loss < 1e-7 && prevLoss - loss >= 0) {
                break;
            }
            prevLoss = loss;

            for (int j = 0; j < m; j++) {
                w[j] -= lr * dw[j];
            }
            b -= lr * db;
        }

        Map<String, Double> weightsMap = new HashMap<>();
        Map<String, Double> meansMap = new HashMap<>();
        Map<String, Double> stddevsMap = new HashMap<>();

        for (int j = 0; j < m; j++) {
            String name = featureNames.get(j);
            weightsMap.put(name, w[j]);
            meansMap.put(name, means[j]);
            stddevsMap.put(name, stddevs[j]);
        }

        return new TrainedApprovalModel("v1", featureNames, b, weightsMap, meansMap, stddevsMap);
    }
}
