package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticRegressionTrainerTest {
    
    @Test
    void testLinearlySeparableSyntheticDataset() {
        var trainer = new LogisticRegressionTrainer();
        int n = 200;
        double[][] X = new double[n][2];
        boolean[] y = new boolean[n];
        
        int idx = 0;
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 10; j++) {
                double x1 = i * 0.5;
                double x2 = j * 1.0;
                X[idx][0] = x1;
                X[idx][1] = x2;
                // linearly separable
                y[idx] = x1 > x2;
                idx++;
            }
        }
        
        var model = trainer.train(X, y, List.of("x1", "x2"), 0.0, 1000);
        
        int correct = 0;
        for (int i = 0; i < n; i++) {
            double prob = model.predict(X[i]);
            boolean pred = prob >= 0.5;
            if (pred == y[i]) {
                correct++;
            }
        }
        
        double accuracy = (double) correct / n;
        assertTrue(accuracy > 0.95, "Accuracy should be > 95%, was: " + accuracy);
    }
    
    @Test
    void testDeterminism() {
        var trainer = new LogisticRegressionTrainer();
        int n = 20;
        double[][] X = new double[n][2];
        boolean[] y = new boolean[n];
        
        for (int i = 0; i < n; i++) {
            X[i][0] = i;
            X[i][1] = n - i;
            y[i] = i > 10;
        }
        
        var model1 = trainer.train(X, y, List.of("x1", "x2"), 0.1, 100);
        var model2 = trainer.train(X, y, List.of("x1", "x2"), 0.1, 100);
        
        double[] weights1 = {model1.weights().get("x1"), model1.weights().get("x2")};
        double[] weights2 = {model2.weights().get("x1"), model2.weights().get("x2")};
        
        assertArrayEquals(weights1, weights2, 0.0, "Weights must be exact bit-identical");
        assertEquals(model1.intercept(), model2.intercept(), 0.0, "Intercept must be exact bit-identical");
    }
    
    @Test
    void testRegularizationL2Shrinkage() {
        var trainer = new LogisticRegressionTrainer();
        int n = 20;
        double[][] X = new double[n][2];
        boolean[] y = new boolean[n];
        
        for (int i = 0; i < n; i++) {
            X[i][0] = i;
            X[i][1] = i * 2;
            y[i] = i > 10;
        }
        
        var modelLow = trainer.train(X, y, List.of("x1", "x2"), 0.0, 500);
        var modelHigh = trainer.train(X, y, List.of("x1", "x2"), 10.0, 500);
        
        double normLow = Math.pow(modelLow.weights().get("x1"), 2) + Math.pow(modelLow.weights().get("x2"), 2);
        double normHigh = Math.pow(modelHigh.weights().get("x1"), 2) + Math.pow(modelHigh.weights().get("x2"), 2);
        
        assertTrue(normHigh < normLow, "Higher lambda should shrink weights");
    }
    
    @Test
    void testStandardizationAndConstantColumn() {
        var trainer = new LogisticRegressionTrainer();
        double[][] X = {
            {2.0, 5.0},
            {4.0, 5.0},
            {6.0, 5.0}
        };
        boolean[] y = {false, true, true};
        var model = trainer.train(X, y, List.of("x1", "const"), 0.0, 10);
        
        assertEquals(4.0, model.means().get("x1"), 1e-6);
        assertEquals(2.0, model.stddevs().get("x1"), 1e-6); // sample stddev
        
        assertEquals(5.0, model.means().get("const"), 1e-6);
        assertEquals(1.0, model.stddevs().get("const"), 1e-6); // exactly 1.0 because stddev ~ 0
    }

    @Test
    void trainThrowsOnLabelsLengthShorterThanFeatures() {
        var trainer = new LogisticRegressionTrainer();
        double[][] X = {{1.0, 2.0}, {3.0, 4.0}, {5.0, 6.0}};
        boolean[] y = {true, false}; // only 2 labels for 3 samples
        assertThrows(IllegalArgumentException.class,
            () -> trainer.train(X, y, List.of("x1", "x2"), 0.0, 10));
    }

    @Test
    void trainThrowsOnLabelsLengthLongerThanFeatures() {
        var trainer = new LogisticRegressionTrainer();
        double[][] X = {{1.0, 2.0}, {3.0, 4.0}};
        boolean[] y = {true, false, true}; // 3 labels for 2 samples
        assertThrows(IllegalArgumentException.class,
            () -> trainer.train(X, y, List.of("x1", "x2"), 0.0, 10));
    }

    @Test
    void trainThrowsOnRaggedFeatureRow() {
        var trainer = new LogisticRegressionTrainer();
        double[][] X = {{1.0, 2.0}, {3.0}}; // second row has only 1 feature
        boolean[] y = {true, false};
        assertThrows(IllegalArgumentException.class,
            () -> trainer.train(X, y, List.of("x1", "x2"), 0.0, 10));
    }
    @Test
    void trainThrowsOnEmptyFeatures() {
        var trainer = new LogisticRegressionTrainer();
        assertThrows(IllegalArgumentException.class,
            () -> trainer.train(new double[0][0], new boolean[0], List.of("x1"), 0.0, 10));
    }
}
