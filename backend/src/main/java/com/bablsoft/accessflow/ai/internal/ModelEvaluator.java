package com.bablsoft.accessflow.ai.internal;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class ModelEvaluator {

    public double calculateAuc(double[] probabilities, boolean[] labels) {
        int n = probabilities.length;
        if (labels.length != n) {
            throw new IllegalArgumentException(
                    "labels.length (" + labels.length + ") must equal probabilities.length (" + n + ")");
        }
        if (n == 0) {
            return 0.5;
        }

        List<Prediction> preds = new ArrayList<>(n);
        long nPos = 0;
        long nNeg = 0;

        for (int i = 0; i < n; i++) {
            preds.add(new Prediction(probabilities[i], labels[i]));
            if (labels[i]) {
                nPos++;
            } else {
                nNeg++;
            }
        }

        if (nPos == 0 || nNeg == 0) {
            return 0.5;
        }

        preds.sort(Comparator.comparingDouble(Prediction::prob));

        double sumPosRanks = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && Math.abs(preds.get(j).prob() - preds.get(i).prob()) < 1e-9) {
                j++;
            }
            double avgRank = (i + 1 + j) / 2.0;
            for (int k = i; k < j; k++) {
                if (preds.get(k).label()) {
                    sumPosRanks += avgRank;
                }
            }
            i = j;
        }

        return (sumPosRanks - (nPos * (nPos + 1.0)) / 2.0) / (double) (nPos * nNeg);
    }

    public double calculateAccuracy(double[] probabilities, boolean[] labels, double threshold) {
        if (labels.length != probabilities.length) {
            throw new IllegalArgumentException(
                    "labels.length (" + labels.length + ") must equal probabilities.length ("
                    + probabilities.length + ")");
        }
        if (probabilities.length == 0) {
            return 1.0;
        }
        int correct = 0;
        for (int i = 0; i < probabilities.length; i++) {
            boolean pred = probabilities[i] >= threshold;
            if (pred == labels[i]) {
                correct++;
            }
        }
        return (double) correct / probabilities.length;
    }

    private record Prediction(double prob, boolean label) {
    }
}
