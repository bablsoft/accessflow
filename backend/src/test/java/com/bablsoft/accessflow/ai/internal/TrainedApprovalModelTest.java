package com.bablsoft.accessflow.ai.internal;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainedApprovalModelTest {

    @Test
    void testJsonRoundTripAndPrediction() {
        var mapper = new ObjectMapper();
        var model = new TrainedApprovalModel(
            "v1",
            List.of("f1", "f2"),
            0.5,
            Map.of("f1", 1.0, "f2", -2.0),
            Map.of("f1", 10.0, "f2", 20.0),
            Map.of("f1", 2.0, "f2", 4.0)
        );

        String json = model.toJson(mapper);
        var loaded = TrainedApprovalModel.fromJson(json, mapper);

        assertEquals(model.featureSchemaVersion(), loaded.featureSchemaVersion());
        assertEquals(model.featureNames(), loaded.featureNames());
        assertEquals(model.intercept(), loaded.intercept());
        assertEquals(model.weights(), loaded.weights());
        assertEquals(model.means(), loaded.means());
        assertEquals(model.stddevs(), loaded.stddevs());

        double prob = model.predict(new double[]{12.0, 16.0});
        double expected = 1.0 / (1.0 + Math.exp(-3.5));
        assertEquals(expected, prob, 1e-6);

        double probLoaded = loaded.predict(new double[]{12.0, 16.0});
        assertEquals(prob, probLoaded, 1e-6);
        assertTrue(prob > 0 && prob < 1.0);
    }

    @Test
    void predictThrowsOnWrongFeatureLength() {
        var model = new TrainedApprovalModel(
            "v1", List.of("f1"),
            0.0, Map.of("f1", 1.0), Map.of("f1", 0.0), Map.of("f1", 1.0));
        assertThrows(IllegalArgumentException.class,
            () -> model.predict(new double[]{1.0, 2.0}));
    }

    @Test
    void constructorRejectsDuplicateFeatureNames() {
        assertThrows(IllegalArgumentException.class, () -> new TrainedApprovalModel(
            "v1",
            List.of("f1", "f1"),          // duplicate
            0.0,
            Map.of("f1", 1.0),
            Map.of("f1", 0.0),
            Map.of("f1", 1.0)));
    }

    @Test
    void constructorRejectsMissingWeight() {
        assertThrows(IllegalArgumentException.class, () -> new TrainedApprovalModel(
            "v1",
            List.of("f1", "f2"),
            0.0,
            Map.of("f1", 1.0),            // f2 weight missing
            Map.of("f1", 0.0, "f2", 0.0),
            Map.of("f1", 1.0, "f2", 1.0)));
    }

    @Test
    void constructorRejectsMissingMean() {
        assertThrows(IllegalArgumentException.class, () -> new TrainedApprovalModel(
            "v1",
            List.of("f1", "f2"),
            0.0,
            Map.of("f1", 1.0, "f2", 2.0),
            Map.of("f1", 0.0),            // f2 mean missing
            Map.of("f1", 1.0, "f2", 1.0)));
    }

    @Test
    void constructorRejectsMissingStddev() {
        assertThrows(IllegalArgumentException.class, () -> new TrainedApprovalModel(
            "v1",
            List.of("f1", "f2"),
            0.0,
            Map.of("f1", 1.0, "f2", 2.0),
            Map.of("f1", 0.0, "f2", 0.0),
            Map.of("f1", 1.0)));          // f2 stddev missing
    }

    @Test
    void fromJsonThrowsApprovalModelSerializationExceptionOnCorruptJson() {
        var mapper = new ObjectMapper();
        assertThrows(ApprovalModelSerializationException.class,
            () -> TrainedApprovalModel.fromJson("{not valid json{{", mapper));
    }

}
