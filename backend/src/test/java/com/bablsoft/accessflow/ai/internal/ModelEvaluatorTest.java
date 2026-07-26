package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelEvaluatorTest {

    @Test
    void testAucPerfectRanking() {
        var eval = new ModelEvaluator();
        double[] probs = {0.1, 0.4, 0.6, 0.9};
        boolean[] labels = {false, false, true, true};
        assertEquals(1.0, eval.calculateAuc(probs, labels), 1e-6);
    }

    @Test
    void testAucInvertedRanking() {
        var eval = new ModelEvaluator();
        double[] probs = {0.9, 0.6, 0.4, 0.1};
        boolean[] labels = {false, false, true, true};
        assertEquals(0.0, eval.calculateAuc(probs, labels), 1e-6);
    }

    @Test
    void testAucRandomTie() {
        var eval = new ModelEvaluator();
        double[] probs = {0.5, 0.5, 0.5, 0.5};
        boolean[] labels = {false, true, false, true};
        assertEquals(0.5, eval.calculateAuc(probs, labels), 1e-6);
    }

    @Test
    void testAucOneClassEmpty() {
        var eval = new ModelEvaluator();
        assertEquals(0.5, eval.calculateAuc(new double[]{0.1, 0.9}, new boolean[]{true, true}), 1e-6);
        assertEquals(0.5, eval.calculateAuc(new double[]{0.1, 0.9}, new boolean[]{false, false}), 1e-6);
    }

    @Test
    void testAccuracy() {
        var eval = new ModelEvaluator();
        double[] probs = {0.1, 0.4, 0.6, 0.9};
        boolean[] labels = {false, true, false, true};
        // 0.1 -> false (Correct)
        // 0.4 -> false (Incorrect)
        // 0.6 -> true (Incorrect)
        // 0.9 -> true (Correct)
        assertEquals(0.5, eval.calculateAccuracy(probs, labels, 0.5), 1e-6);
    }
}
